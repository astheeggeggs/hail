package org.broadinstitute.hail.driver

import org.apache.spark.RangePartitioner
import org.apache.spark.sql.Row
import org.apache.spark.storage.StorageLevel
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.expr._
import org.broadinstitute.hail.variant.{Variant, Genotype}
import org.broadinstitute.hail.annotations._
import org.kohsuke.args4j.{Option => Args4jOption}
import java.time._
import scala.collection.mutable
import scala.io.Source

object ExportVCF extends Command {

  class Options extends BaseOptions {
    @Args4jOption(name = "-a", usage = "Append file to header")
    var append: String = _

    @Args4jOption(required = true, name = "-o", aliases = Array("--output"), usage = "Output file")
    var output: String = _

  }

  def newOptions = new Options

  def name = "exportvcf"

  def description = "Write current dataset as VCF file"

  override def supportsMultiallelic = true

  def infoNumber(t: Type): String = t match {
    case TBoolean => "0"
    case TArray(elementType) => "."
    case _ => "1"
  }

  def infoType(t: Type): String = t match {
    case TArray(elementType) => infoType(elementType)
    case TInt => "Integer"
    case TDouble => "Float"
    case TChar => "Character"
    case TString => "String"
    case TBoolean => "Flag"

    // FIXME
    case _ => "String"
  }

  def run(state: State, options: Options): State = {
    val vds = state.vds
    val vas = vds.vaSignature

    val infoSignature = vds.vaSignature
      .getAsOption[TStruct]("info")
    val infoQuery: Querier = infoSignature match {
      case Some(_) => vas.query("info")
      case None => a => None
    }

    def header: String = {
      val sb = new StringBuilder()

      sb.append("##fileformat=VCFv4.2\n")
      sb.append(s"##fileDate=${LocalDate.now}\n")
      // FIXME add Hail version
      sb.append(
        """##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
          |##FORMAT=<ID=AD,Number=R,Type=Integer,Description="Allelic depths for the ref and alt alleles in the order listed">
          |##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Read Depth">
          |##FORMAT=<ID=GQ,Number=1,Type=Integer,Description="Genotype Quality">
          |##FORMAT=<ID=PL,Number=G,Type=Integer,Description="Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification">""".stripMargin)
      sb += '\n'

      vds.filters.map { case (key, desc) =>
        sb.append(s"""##FILTER=<ID=$key,Description="$desc">\n""")
      }

      infoSignature.foreach(_.fields.foreach { f =>
        sb.append("##INFO=<ID=")
        sb.append(f.name)
        sb.append(",Number=")
        sb.append(f.attr("Number").getOrElse(infoNumber(f.`type`)))
        sb.append(",Type=")
        sb.append(infoType(f.`type`))
        f.attr("Description") match {
          case Some(d) =>
            sb.append(",Description=\"")
            sb.append(d)
            sb += '"'
          case None =>
        }
        sb.append(">\n")
      })

      if (options.append != null) {
        readFile(options.append, state.hadoopConf) { s =>
          Source.fromInputStream(s)
            .getLines()
            .filterNot(_.isEmpty)
            .foreach { line =>
              sb.append(line)
              sb += '\n'
            }
        }
      }

      sb.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO")
      if (vds.nLocalSamples > 0)
        sb.append("\tFORMAT")
      val sampleIds: Array[String] = vds.localSamples.map(vds.sampleIds)
      sampleIds.foreach { id =>
        sb += '\t'
        sb.append(id)
      }
      sb.result()
    }

    val idQuery: Option[Querier] = vas.getOption("rsid")
      .map(_ => vds.queryVA("rsid"))

    val qualQuery: Option[Querier] = vas.getOption("qual")
      .map(_ => vds.queryVA("qual"))

    val filterQuery: Option[Querier] = vas.getOption("filters")
      .map(_ => vds.queryVA("filters"))

    val hasGenotypes = vds.nLocalSamples > 0
    def appendRow(sb: StringBuilder, v: Variant, a: Annotation, gs: Iterable[Genotype]) {

      sb.append(v.contig)
      sb += '\t'
      sb.append(v.start)
      sb += '\t'

      sb.append(idQuery.flatMap { q => q(a) }
        .getOrElse("."))

      sb += '\t'
      sb.append(v.ref)
      sb += '\t'
      v.altAlleles.foreachBetween(aa =>
        sb.append(aa.alt))(() => sb += ',')
      sb += '\t'

      sb.append(qualQuery.flatMap { q => q(a) }
        .map(_.asInstanceOf[Double].formatted("%.2f"))
        .getOrElse("."))

      sb += '\t'

      filterQuery.flatMap { q => q(a) }
        .map(_.asInstanceOf[mutable.WrappedArray[String]]) match {
        case Some(f) =>
          if (f.nonEmpty)
            f.foreachBetween { s =>
              sb.append(s)
            } { () =>
              sb += ','
            }
          else
            sb += '.'
        case None => sb += '.'
      }

      sb += '\t'

      infoQuery(a).map(_.asInstanceOf[Row]) match {
        case Some(r) =>
          infoSignature.get.fields
            .zip(r.toSeq)
            .foreachBetween { case (f, v) =>
              if (v != null) {
                sb.append(f.name)
                if (f.`type` != TBoolean) {
                  sb += '='
                  v match {
                    case i: Iterable[_] => i.foreachBetween { elem => sb.append(elem) } { () => sb.append(",") }
                    case _ => sb.append(v)
                  }
                }
              }
            } { () => sb += ';' }

        case None =>
          sb += '.'
      }

      if (hasGenotypes) {
        sb += '\t'
        sb.append("GT:AD:DP:GQ:PL")
        gs.foreach { g =>
          sb += '\t'
          sb.append(g)
        }
      }
    }

    val kvRDD = vds.rdd.map { case (v, a, gs) =>
      (v, (a, gs.toGenotypeStream(v, compress = false)))
    }
    kvRDD.persist(StorageLevel.MEMORY_AND_DISK)
    kvRDD
      .repartitionAndSortWithinPartitions(new RangePartitioner[Variant, (Annotation, Iterable[Genotype])](vds.rdd.partitions.length, kvRDD))
      .mapPartitions { it: Iterator[(Variant, (Annotation, Iterable[Genotype]))] =>
        val sb = new StringBuilder
        it.map { case (v, (va, gs)) =>
          sb.clear()
          appendRow(sb, v, va, gs)
          sb.result()
        }
      }.writeTable(options.output, Some(header), deleteTmpFiles = true)
    kvRDD.unpersist()
    state
  }

}
