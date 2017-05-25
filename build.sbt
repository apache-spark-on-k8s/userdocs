name := "spark-on-k8s-doc"

organization := "org.sparkonk8s"  // something else preferable here?

version := "0.1.0"

scalaVersion := "2.11.8"

licenses += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0"))

enablePlugins(GhpagesPlugin, JekyllPlugin)

git.remoteRepo := "git@github.com:apache-spark-on-k8s/spark-on-k8s-doc.git"
