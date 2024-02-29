object Test {
  def main(args: Array[String]): Unit = {
    val mixedList = List(1,(1,2),4,(3,1),(5,4),6)
    val as = for(case (a,b) <- mixedList) yield a
    println(as.mkString(", "))
  }
}
