import sbt._

class AntsyProject(info: ProjectInfo) extends DefaultProject(info) with ProguardSpdeProject {
  val ccstmRepo = "CCSTM Repo" at "http://ppl.stanford.edu/ccstm/repo-releases"

  val ccstm = "edu.stanford.ppl" % "ccstm" % "0.2.1-for-scala-2.8.0"
  val hawtDispatch = "org.fusesource.hawtdispatch" % "hawtdispatch-scala" % "1.0"

  override def spdeSourcePath = mainSourcePath / "spde"
}
