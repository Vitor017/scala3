//> using options -Xfatal-warnings

class C[T] {
  val x: Any = ???
  if (x.isInstanceOf[List[String]]) // warn: unchecked
    if (x.isInstanceOf[T])          // warn: unchecked
      x match {
        case x: List[String] =>     // warn: unchecked
        case x: T =>                // warn: unchecked
      }
}
// nopos-error: No warnings can be incurred under -Werror.