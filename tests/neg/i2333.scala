//> using options -Xfatal-warnings -deprecation

@deprecated("bla", "2.11.0") class Foo {
  println("")
  def this(x: Int) = this()
}

object Test {
  new Foo // warn: deprecated
  new Foo(1) // warn: deprecated
}
// nopos-error: No warnings can be incurred under -Werror.