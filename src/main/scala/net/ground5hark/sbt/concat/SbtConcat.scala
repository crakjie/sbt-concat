package net.ground5hark.sbt.concat

import com.typesafe.sbt.web.{PathMapping, SbtWeb}
import sbt.Keys._
import sbt._
import com.typesafe.sbt.web.pipeline.Pipeline
import collection.mutable
import mutable.ListBuffer

object Import {
  val concat = TaskKey[Pipeline.Stage]("web-concat", "Concatenates groups of web assets")

  object Concat {
    val groups = SettingKey[Seq[ConcatGroup]]("web-concat-groups", "List of ConcatGroup items")
    val parentDir = SettingKey[String]("web-concat-parent-dir", "Parent directory name in the target folder to write concatenated files to, default: \"concat\"")
  }
}

object NotHiddenFileFilter extends FileFilter {
  override def accept(f: File): Boolean = !HiddenFileFilter.accept(f)
}

object SbtConcat extends AutoPlugin {
  override def requires = SbtWeb

  override def trigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport._
  import Concat._

  override def projectSettings = Seq(
    groups := ListBuffer.empty[ConcatGroup],
    includeFilter in concat := NotHiddenFileFilter,
    parentDir := "concat",
    concat := concatFiles.value
  )

  private def concatFiles: Def.Initialize[Task[Pipeline.Stage]] = Def.task {
    mappings: Seq[PathMapping] =>
      val groupsValue = groups.value

      val groupMappings = if (groupsValue.nonEmpty) {
        streams.value.log.info(s"Building ${groupsValue.size} concat group(s)")
        // Mutable map so we can pop entries we've already seen, in case there are similarly named files
        val reverseMapping = ReverseGroupMapping.get(groupsValue, streams.value.log)
        val concatGroups = mutable.Map.empty[String, StringBuilder]
        val filteredMappings = mappings.filter(m => (includeFilter in concat).value.accept(m._1) && m._1.isFile)

        groupsValue.foreach {
          case (groupName, fileNames) =>
            fileNames.foreach { fileName =>
              val mapping = filteredMappings.filter(_._2 == fileName)
              if (mapping.nonEmpty) {
                // TODO This is not as memory efficient as it could be, write to file instead
                concatGroups.getOrElseUpdate(groupName, new StringBuilder)
                  .append(s"\n/** $fileName **/\n")
                  .append(IO.read(mapping.head._1))
                reverseMapping.remove(fileName)
              }
            }
        }

        val targetDir = (public in Assets).value / parentDir.value
        concatGroups.map {
          case (groupName, concatenatedContents) =>
            val outputFile = targetDir / groupName
            IO.write(outputFile, concatenatedContents.toString)
            (outputFile, s"concat/$groupName")
        }.toSeq
      } else {
        Seq.empty[PathMapping]
      }

      groupMappings ++ mappings
  }
}

private object ReverseGroupMapping {
  def get(groups: Seq[ConcatGroup], logger: Logger): mutable.Map[String, String] = {
    val ret = mutable.Map.empty[String, String]
    groups.foreach {
      case (groupName, fileNames) => fileNames.foreach { fileName =>
        ret(fileName) = groupName
      }
    }
    ret
  }
}
