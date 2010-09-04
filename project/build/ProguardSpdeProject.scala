import sbt._
import spde._

trait ProguardSpdeProject extends SpdeProject with BasicPackagePaths {
  def proguardConfigurationPath: Path = outputPath / "proguard.pro"
  def proguardedJarName = artifactID + "-proguarded-" + version + ".jar"
  def proguardedJar = outputPath / proguardedJarName

  def rootProjectDirectory = rootProject.info.projectPath

  // Dependencies

  val toolsConfig = config("tools")
  val proguardDep = "net.sf.proguard" % "proguard" % "4.4" % "tools"

  // Proguard

  lazy val proguard = proguardTask dependsOn(`package`, writeProguardConfiguration)
  lazy val writeProguardConfiguration = writeProguardConfigurationTask dependsOn `package`

  private def proguardTask = fileTask(proguardedJar from sourceGlob) {
    FileUtilities.clean(proguardedJar :: Nil, log)
    val proguardClasspathString = Path.makeString(managedClasspath(toolsConfig).get)
    val configFile = proguardConfigurationPath.toString
    val exitValue = Process("java", List("-Xmx256M", "-cp", proguardClasspathString, "proguard.ProGuard", "@" + configFile)) ! log
    if(exitValue == 0) None else Some("Proguard failed with nonzero exit code (" + exitValue + ")")
  }

  def proguardOptions = "-dontobfuscate" :: "-dontoptimize" :: "-dontnote" :: "-dontwarn" :: "-ignorewarnings" :: Nil

  private def writeProguardConfigurationTask = fileTask(proguardConfigurationPath from sourceGlob) {
      // the template for the proguard configuration file
      val outTemplate = """
        |%s
        |-outjars %s
        |-keep class %s { public void main(...); }
        |-keep class %s
        |-keep class spde.core.SApplet { *** scripty(...); }
        |-keepclasseswithmembers class * { public void dispose(); }
        |-keep class processing.core.PGraphicsJava2D { *** <init>(...); }
        |"""

      val defaultJar = (outputPath / defaultJarName).asFile.getAbsolutePath

      log.debug("proguard configuration using main jar " + defaultJar)

      val externalDependencies = Set() ++ (
        mainCompileConditional.analysis.allExternals ++ compileClasspath.get.map { _.asFile }
      ) map { _.getAbsoluteFile } filter {  _.getName.endsWith(".jar") }

      def quote(s: Any) = '"' + s.toString + '"'

      log.debug("proguard configuration external dependencies: \n\t" + externalDependencies.mkString("\n\t"))

      val (projectJars, otherJars) = externalDependencies.toList.partition(jar => Path.relativize(rootProjectDirectory, jar).isDefined)
      val inJars = (quote(defaultJar) :: projectJars.map(quote(_) + "(!META-INF/**,!*.txt)")).map("-injars " + _)
      val libraryJars = otherJars.map(quote).map { "-libraryjars " + _ }

      val proguardConfiguration = outTemplate.stripMargin.format(
        (proguardOptions ++ inJars ++ libraryJars).mkString("\n"),
        quote(proguardedJar.absolutePath),
        runnerClass,
        sketchClass
      )

      log.debug("Proguard configuration written to " + proguardConfigurationPath)

      FileUtilities.write(proguardConfigurationPath.asFile, proguardConfiguration, log)
    }
}
