package is.hail.methods

import breeze.linalg._
import breeze.stats.mean
import is.hail.annotations._
import is.hail.expr.{TDouble, TStruct}
import is.hail.stats._
import is.hail.utils._
import is.hail.variant.Variant
import is.hail.{SparkSuite, TestUtils}
import org.testng.annotations.Test

class LinearMixedRegressionSuite extends SparkSuite {

  @Test def lmmSmallExampleTest() {

    val y = DenseVector(0d, 0d, 1d, 1d, 1d, 1d)

    val C = DenseMatrix(
      (1.0, 0.0, -1.0),
      (1.0, 2.0, 3.0),
      (1.0, 1.0, 5.0),
      (1.0, -2.0, 0.0),
      (1.0, -2.0, -4.0),
      (1.0, 4.0, 3.0))

    val G = DenseMatrix(
      (0, 1, 1, 2),
      (1, 0, 2, 2),
      (2, 0, 0, 2),
      (0, 0, 1, 1),
      (0, 0, 0, 1),
      (2, 1, 0, 0))

    val n = y.length
    val c = C.cols

    val W = convert(G(::, 0 to 1), Double)

    // each row has mean 0, norm sqrt(n), variance 1
    for (i <- 0 until W.cols) {
      W(::, i) -= mean(W(::, i))
      W(::, i) *= math.sqrt(n) / norm(W(::, i))
    }

    val mW = W.cols
    val mG = G.cols

    val rrm = (W * W.t) / mW.toDouble // RRM
    val delta = 2.23

    // Now testing global model
    // First solve directly with Cholesky
    val V = rrm + DenseMatrix.eye[Double](n) * delta

    val invChol = inv(cholesky(V))

    val yc = invChol * y
    val Cc = invChol * C

    val beta = (Cc.t * Cc) \ (Cc.t * yc)
    val res = norm(yc - Cc * beta)
    val sg2 = (res * res) / (n - c)
    val se2 = delta * sg2
    val h2 = sg2 / (se2 + sg2)

    // Then solve with DiagLMM and compare
    val eigRRM = eigSymD(rrm)
    val Ut = eigRRM.eigenvectors.t
    val S = eigRRM.eigenvalues

    val yr = Ut * y
    val Cr = Ut * C

    val model = DiagLMM(Cr, yr, S, Some(delta))

    TestUtils.assertVectorEqualityDouble(beta, model.globalB)
    assert(D_==(sg2, model.globalS2))

    val modelML = DiagLMM(Cr, yr, S, Some(delta), useML = true)

    TestUtils.assertVectorEqualityDouble(beta, modelML.globalB)
    assert(D_==(sg2 * (n - c) / n, modelML.globalS2))

    // Now testing association per variant
    // First solve directly with Cholesky
    val directResult = (0 until mG).map { j =>
      val xIntArray = G(::, j).toArray
      val x = convert(G(::, j to j), Double)
      val xC = DenseMatrix.horzcat(x, C)
      val xCc = invChol * xC
      val beta = (xCc.t * xCc) \ (xCc.t * yc)
      val res = norm(yc - xCc * beta)
      val sg2 = (res * res) / (n - c)
      val chi2 = n * (model.logNullS2- math.log(sg2))
      val pval = chiSquaredTail(1d, chi2)
      val nHomRef = xIntArray.count(_ == 0)
      val nHet = xIntArray.count(_ == 1)
      val nHomVar = xIntArray.count(_ == 2)
      val nMissing = xIntArray.count(_ == -1)
      val af = (nHet + 2 * nHomVar).toDouble / (2 * (n - nMissing))
      (Variant("1", j + 1, "A", "C"), (beta(0), sg2, chi2, pval, af, nHomRef, nHet, nHomVar, nMissing))
    }.toMap

    // Then solve with LinearMixeModel and compare
    val vds0 = vdsFromMatrix(hc)(G)
    val pheno = y.toArray
    val cov1 = C(::, 1).toArray
    val cov2 = C(::, 2).toArray

    val assocVds = vds0
      .annotateSamples(vds0.sampleIds.zip(pheno).toMap, TDouble, "sa.pheno")
      .annotateSamples(vds0.sampleIds.zip(cov1).toMap, TDouble, "sa.cov1")
      .annotateSamples(vds0.sampleIds.zip(cov2).toMap, TDouble, "sa.cov2")
    val kinshipVds = assocVds.filterVariants((v, va, gs) => v.start <= 2)

    val vds = LinearMixedRegression(assocVds, kinshipVds.rrm(), "sa.pheno", covSA = Array("sa.cov1", "sa.cov2"),
      useML = false, rootGA = "global.lmmreg", rootVA = "va.lmmreg", runAssoc = true, optDelta = Some(delta),
      sparsityThreshold = 1.0)

    val qBeta = vds.queryVA("va.lmmreg.beta")._2
    val qSg2 = vds.queryVA("va.lmmreg.sigmaG2")._2
    val qChi2 = vds.queryVA("va.lmmreg.chi2")._2
    val qPval = vds.queryVA("va.lmmreg.pval")._2
    val qAF = vds.queryVA("va.lmmreg.AF")._2
    val qnHomRef = vds.queryVA("va.lmmreg.nHomRef")._2
    val qnHet = vds.queryVA("va.lmmreg.nHet")._2
    val qnHomVar = vds.queryVA("va.lmmreg.nHomVar")._2
    val qnMissing = vds.queryVA("va.lmmreg.nMissing")._2

    val annotationMap = vds.variantsAndAnnotations.collect().toMap

    def assertInt(q: Querier, v: Variant, value: Int) = q(annotationMap(v)).asInstanceOf[Int] == value

    def assertDouble(q: Querier, v: Variant, value: Double) = {
      val x = q(annotationMap(v)).asInstanceOf[Double]
      assert(D_==(x, value))
    }

    (0 until mG).foreach { j =>
      val v = Variant("1", j + 1, "A", "C")
      val (beta, sg2, chi2, pval, af, nHomRef, nHet, nHomVar, nMissing) = directResult(v)
      assertDouble(qBeta, v, beta)
      assertDouble(qSg2, v, sg2)
      assertDouble(qChi2, v, chi2)
      assertDouble(qPval, v, pval)
      assertDouble(qAF, v, af)
      assertInt(qnHomRef, v, nHomRef)
      assertInt(qnHet, v, nHet)
      assertInt(qnHomVar, v, nHomVar)
      assertInt(qnMissing, v, nMissing)
    }
  }

  @Test def lmmLargeExampleTest() {
    val seed = 0
    scala.util.Random.setSeed(seed)

    val n = 100
    val c = 3 // number of covariates including intercept
    val m0 = 300
    val k = 10
    val Fst = .2

    val y = DenseVector.fill[Double](n)(scala.util.Random.nextGaussian())

    val C =
      if (c == 1)
        DenseMatrix.ones[Double](n, 1)
      else
        DenseMatrix.horzcat(DenseMatrix.ones[Double](n, 1), DenseMatrix.fill[Double](n, c - 1)(scala.util.Random.nextGaussian()))

    val FstOfPop = Array.fill[Double](k)(Fst)

    val bnm = BaldingNicholsModel(hc, k, n, m0, None, Some(FstOfPop), scala.util.Random.nextInt(), Some(4), UniformDist(.1, .9))

    val G = TestUtils.removeConstantCols(TestUtils.vdsToMatrixInt(bnm))

    val mG = G.cols
    val mW = G.cols

    // println(s"$mG of $m0 variants are not constant")

    val W = convert(G(::, 0 until mW), Double)

    // each row has mean 0, norm sqrt(n), variance 1
    // each row has mean 0, norm sqrt(n), variance 1
    for (i <- 0 until mW) {
      W(::, i) -= mean(W(::, i))
      W(::, i) *= math.sqrt(n) / norm(W(::, i))
    }

    val rrm = (W * W.t) / mW.toDouble // RRM
    val delta = scala.util.Random.nextGaussian()

    // Now testing global model
    // First solve directly with Cholesky
    val V = rrm + DenseMatrix.eye[Double](n) * delta

    val invChol = inv(cholesky(V))

    val yc = invChol * y
    val Cc = invChol * C

    val beta = (Cc.t * Cc) \ (Cc.t * yc)
    val res = norm(yc - Cc * beta)
    val sg2 = (res * res) / (n - c)
    val se2 = delta * sg2
    val h2 = sg2 / (se2 + sg2)

    // println(beta, sg2, se2, h2)

    // Then solve with DiagLMM and compare
    val eigRRM = eigSymD(rrm)
    val Ut = eigRRM.eigenvectors.t
    val S = eigRRM.eigenvalues

    val yr = Ut * y
    val Cr = Ut * C

    val model = DiagLMM(Cr, yr, S, Some(delta))

    TestUtils.assertVectorEqualityDouble(beta, model.globalB)
    assert(D_==(sg2, model.globalS2))

    val modelML = DiagLMM(Cr, yr, S, Some(delta), useML = true)

    TestUtils.assertVectorEqualityDouble(beta, modelML.globalB)
    assert(D_==(sg2 * (n - c) / n, modelML.globalS2))

    // Now testing association per variant
    // First solve directly with Cholesky
    val directResult = (0 until mG).map { j =>
      val x = convert(G(::, j to j), Double)
      val xC = DenseMatrix.horzcat(x, C)
      val xCc = invChol * xC
      val beta = (xCc.t * xCc) \ (xCc.t * yc)
      val res = norm(yc - xCc * beta)
      val sg2 = (res * res) / (n - c)
      (Variant("1", j + 1, "A", "C"), (beta(0), sg2))
    }.toMap

    // Then solve with LinearMixedModel and compare

    val pheno = y.toArray
    val covSA = (1 until c).map(i => s"sa.covs.cov$i").toArray
    val covSchema = TStruct((1 until c).map(i => (s"cov$i", TDouble)): _*)
    val covData = bnm.sampleIds.zipWithIndex.map { case (id, i) => (id, Annotation.fromSeq( C(i, 1 until c).t.toArray)) }.toMap

    val assocVds = bnm
      .annotateSamples(bnm.sampleIds.zip(pheno).toMap, TDouble, "sa.pheno")
      .annotateSamples(covData, covSchema, "sa.covs")
    val kinshipVds = assocVds.filterVariants((v, va, gs) => v.start <= mW)

    val vds = LinearMixedRegression(assocVds, kinshipVds.rrm(), "sa.pheno", covSA = covSA,
      useML = false, rootGA = "global.lmmreg", rootVA = "va.lmmreg", runAssoc = true, optDelta = Some(delta),
      sparsityThreshold = 1.0)

    val qBeta = vds.queryVA("va.lmmreg.beta")._2
    val qSg2 = vds.queryVA("va.lmmreg.sigmaG2")._2

    val annotationMap = vds.variantsAndAnnotations.collect().toMap

    def assertDouble(q: Querier, v: Variant, value: Double) = {
      val x = q(annotationMap(v)).asInstanceOf[Double]
      // println(x, value, v)
      assert(D_==(x, value))
    }

    (0 until mG).foreach { j =>
      val v = Variant("1", j + 1, "A", "C")
      val (beta, sg2) = directResult(v)
      assertDouble(qBeta, v, beta)
      assertDouble(qSg2, v, sg2)
    }
  }

  @Test def fastlmmTest() {
    /*
    Test data is from all.bed, all.bim, all.fam, cov.txt, pheno_10_causals.txt:
    https://github.com/MicrosoftGenomics/FaST-LMM/tree/master/tests/datasets/synth

    Data is filtered to chromosome 1,3 and samples 0-124,375-499 (2000 variants and 250 samples)

    Results are computed with single_snp as in:
    https://github.com/MicrosoftGenomics/FaST-LMM/blob/master/doc/ipynb/FaST-LMM.ipynb
    */

    val vds = hc.importPlink(bed="src/test/resources/fastlmmTest.bed", bim="src/test/resources/fastlmmTest.bim", fam="src/test/resources/fastlmmTest.fam")
      .annotateSamplesTable("src/test/resources/fastlmmCov.txt", "_1", code=Some("sa.cov=table._2"), config=TextTableConfiguration(noHeader=true, impute=true))
      .annotateSamplesTable("src/test/resources/fastlmmPheno.txt", "_1", code=Some("sa.pheno=table._2"), config=TextTableConfiguration(noHeader=true, impute=true, separator=" "))

    val vdsChr1 = vds.filterVariantsExpr("""v.contig == "1"""").lmmreg(vds.filterVariantsExpr("""v.contig != "1"""").rrm(), "sa.pheno", Array("sa.cov"), useML = false, rootGA = "global.lmmreg", rootVA = "va.lmmreg", runAssoc = false, optDelta = None, sparsityThreshold = 1.0)

    val vdsChr3 = vds.filterVariantsExpr("""v.contig == "3"""").lmmreg(vds.filterVariantsExpr("""v.contig != "3"""").rrm(), "sa.pheno", Array("sa.cov"), useML = false, rootGA = "global.lmmreg", rootVA = "va.lmmreg", runAssoc = false, optDelta = None, sparsityThreshold = 1.0)

    val h2Chr1 = vdsChr1.queryGlobal("global.lmmreg.h2")._2.asInstanceOf[Double]
    val h2Chr3 = vdsChr3.queryGlobal("global.lmmreg.h2")._2.asInstanceOf[Double]

    assert(D_==(h2Chr1, 0.36733239840887433))
    assert(D_==(h2Chr3, 0.14276116822096985))
  }

  // this test parallels the lmmreg Python test, and is a regression test related to filtering samples first
  @Test def filterTest() {

    var vdsAssoc = hc.importVCF("src/test/resources/regressionLinear.vcf")
      .filterMulti()
      .annotateSamplesTable("src/test/resources/regressionLinear.cov", "Sample", root = Some("sa.cov"), config = TextTableConfiguration(types = Map("Cov1" -> TDouble, "Cov2" -> TDouble)))
      .annotateSamplesTable("src/test/resources/regressionLinear.pheno", "Sample", code = Some("sa.pheno.Pheno = table.Pheno"), config = TextTableConfiguration(types = Map("Pheno" -> TDouble), missing = "0"))
      .annotateSamplesExpr("""sa.culprit = gs.filter(g => v == Variant("1", 1, "C", "T")).map(g => g.gt).collect()[0]""")
      .annotateSamplesExpr("sa.pheno.PhenoLMM = (1 + 0.1 * sa.cov.Cov1 * sa.cov.Cov2) * sa.culprit")

    val vdsKinship = vdsAssoc.filterVariantsExpr("v.start < 4")

    vdsAssoc = vdsAssoc.lmmreg(vdsKinship.rrm(), "sa.pheno.PhenoLMM", Array("sa.cov.Cov1", "sa.cov.Cov2"), useML = false, rootGA = "global.lmmreg", rootVA = "va.lmmreg", runAssoc = false, optDelta = None, sparsityThreshold = 1.0)

    vdsAssoc.count()
  }
}
