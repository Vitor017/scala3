//> using options -Xfatal-warnings

@SerialVersionUID(1L) // warn
trait T

object Test {
  var t: T = compiletime.uninitialized
  def main(args: Array[String]) = println("hi")
}
// nopos-error: No warnings can be incurred under -Werror.
