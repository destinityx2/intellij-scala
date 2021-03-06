package org.jetbrains.plugins.scala.annotator.intention.sbt.ui

import com.intellij.ide.wizard.{AbstractWizard, Step}
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.annotator.intention.sbt.DependencyPlaceInfo
import org.jetbrains.sbt.resolvers.ArtifactInfo

/**
  * Created by afonichkin on 7/18/17.
  */
class SbtArtifactSearchWizard(project: Project, artifactInfoSet: Set[ArtifactInfo], fileLines: Seq[DependencyPlaceInfo])
  extends AbstractWizard[Step]("", project) {

  val sbtArtifactSearchStep = new SbtArtifactChooseDependencyStep(this, artifactInfoSet)
  val sbtPossiblePlacesStep = new SbtPossiblePlacesStep(this, project, fileLines)

  var resultArtifact: Option[ArtifactInfo] = _
  var resultFileLine: Option[DependencyPlaceInfo] = _

  override def init(): Unit = {
    super.init()
  }

  override def getHelpID: String = null

  def search(): (Option[ArtifactInfo], Option[DependencyPlaceInfo]) = {
    if (!showAndGet()) {
      return (None, None)
    }

    (resultArtifact, resultFileLine)
  }

  override def canGoNext: Boolean = {
    if (getCurrentStepObject == sbtArtifactSearchStep) {
      sbtArtifactSearchStep.canGoNext
    } else {
      sbtPossiblePlacesStep.canGoNext
    }
  }

  addStep(sbtArtifactSearchStep)
  addStep(sbtPossiblePlacesStep)
  init()
}
