package is.hail.asm4s

import is.hail.check.{Gen, Prop}
import org.scalatest.testng.TestNGSuite
import org.testng.annotations.Test

class ASM4SSuite extends TestNGSuite {
  @Test def not(): Unit = {
    val notb = new FunctionZToZBuilder
    val not = notb.result(!notb.arg1)
    assert(!not(true))
    assert(not(false))
  }

  @Test def mux(): Unit = {
    val gb = new FunctionZToIBuilder
    val g = gb.result(gb.arg1.mux(11, -1))
    assert(g(true) == 11)
    assert(g(false) == -1)
  }

  @Test def add(): Unit = {
    val fb = new FunctionIToIBuilder
    val f = fb.result(fb.arg1 + 5)
    assert(f(-2) == 3)
  }

  @Test def array(): Unit = {
    val hb = new FunctionIToIBuilder
    val arr = hb.newLocal[Array[Int]]()
    val h = hb.result(Code(
      arr.store(Code.newArray[Int](3)),
      arr(0) = 6,
      arr(1) = 7,
      arr(2) = -6,
      arr(hb.arg1)
    ))
    assert(h(0) == 6)
    assert(h(1) == 7)
    assert(h(2) == -6)
  }

  @Test def get(): Unit = {
    val ib = new FunctionAToIBuilder[A]
    val i = ib.result(ib.arg1.get[Int]("i"))

    val a = new A
    assert(i(a) == 5)
  }

  @Test def invoke(): Unit = {
    val ib = new FunctionAToIBuilder[A]
    val i = ib.result(ib.arg1.invoke[Int]("f"))

    val a = new A
    assert(i(a) == 6)
  }

  @Test def invoke2(): Unit = {
    val jb = new FunctionAToIBuilder[A]
    val j = jb.result(jb.arg1.invoke[Int, Int]("g", 6))

    val a = new A
    assert(j(a) == 11)
  }

  @Test def newInstance(): Unit = {
    val fb = new FunctionToIBuilder
    val f = fb.result(
      Code.newInstance[A]().invoke[Int]("f"))
    assert(f() == 6)
  }

  @Test def put(): Unit = {
    val fb = new FunctionToIBuilder
    val inst = fb.newLocal[A]()
    val f = fb.result(Code(
      inst.store(Code.newInstance[A]()),
      inst.put("i", -2),
      inst.get[Int]("i")))
    assert(f() == -2)
  }

  @Test def staticPut(): Unit = {
    val fb = new FunctionToIBuilder
    val inst = fb.newLocal[A]()
    val f = fb.result(Code(
      inst.store(Code.newInstance[A]()),
      inst.put("j", -2),
      fb.getStatic[A, Int]("j")))
    assert(f() == -2)
  }

  @Test def f2(): Unit = {
    val fb = new FunctionIAndIToIBuilder
    val f = fb.result(fb.arg1 + fb.arg2)
    assert(f(3, 5) == 8)
  }

  @Test def compare(): Unit = {
    val fb = new FunctionIAndIToZBuilder
    val f = fb.result(fb.arg1 > fb.arg2)
    assert(f(5, 2))
    assert(!f(-1, -1))
    assert(!f(2, 5))
  }

  @Test def fact(): Unit = {
    val fb = new FunctionIToIBuilder
    val i = fb.arg1
    val r = fb.newLocal[Int]()
    val f = fb.result(Code(
      r.store(1),
      Code.whileLoop(
        fb.arg1 > 1,
        Code(
          r.store(r * i),
          i.store(i - 1))),
      r))

    assert(f(3) == 6)
    assert(f(4) == 24)
  }

  @Test def dcmp(): Unit = {
    val fb = new FunctionDAndDToZBuilder
    val f = fb.result(fb.arg1 > fb.arg2)
    assert(f(5.2, 2.3))

    val d = -2.3
    assert(!f(d, d))
    assert(!f(2.3, 5.2))
  }

  @Test def anewarray(): Unit = {
    val fb = new FunctionToIBuilder
    val arr = fb.newLocal[Array[A]]()
    val f = fb.result(Code(
      arr.store(Code.newArray[A](2)),
      arr(0) = Code.newInstance[A](),
      arr(1) = Code.newInstance[A](),
      arr(0).get[Int]("i") + arr(1).get[Int]("i")
    ))
    assert(f() == 10)
  }

  def fibonacciReference(i: Int): Int = i match {
    case 0 => 0
    case 1 => 1
    case n => fibonacciReference(n-1) + fibonacciReference(n-2)
  }

  @Test def fibonacci(): Unit = {
    val fb = new FunctionIToIBuilder
    val i = fb.arg1
    val n = fb.newLocal[Int]
    val vn_2 = fb.newLocal[Int]
    val vn_1 = fb.newLocal[Int]
    val temp = fb.newLocal[Int]
    val f = fb.result(
      (i < 3).mux(1, Code(
        vn_2.store(1),
        vn_1.store(1),
        Code.whileLoop(
          i > 3,
          Code(
            temp.store(vn_2 + vn_1),
            vn_2.store(vn_2),
            vn_1.store(temp),
            i.store(i - 1)
          )
        ),
        vn_2 + vn_1
      ))
    )

    Prop.forAll(Gen.choose(0, 100)) { i =>
      fibonacciReference(i) == f(i)
    }
  }

}
