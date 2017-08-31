package org.jetbrains.plugins.scala.annotator.intention.sbt

import com.intellij.openapi.application.Result
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.plugins.scala.annotator.intention.sbt.SbtDependenciesVisitor._
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
import org.jetbrains.plugins.scala.lang.psi.api.{ScalaElementVisitor, ScalaFile}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.{ScParameterizedType, ScType}
import org.jetbrains.plugins.scala.project.ProjectContext
import org.jetbrains.sbt.resolvers.ArtifactInfo

/**
  * Created by afonichkin on 7/21/17.
  */
object AddSbtDependencyUtils {
  val LIBRARY_DEPENDENCIES: String = "libraryDependencies"
  val SETTINGS: String = "settings"
  val SEQ: String = "Seq"

  val SBT_PROJECT_TYPE = "_root_.sbt.Project"
  val SBT_SEQ_TYPE = "_root_.scala.collection.Seq"
  val SBT_SETTING_TYPE = "_root_.sbt.Def.Setting"

  def getPossiblePlacesToAddFromProjectDefinition(proj: ScPatternDefinition): Seq[PsiElement] = {
    var res: Seq[PsiElement] = List()

    def action(psiElement: PsiElement): Unit = {
      psiElement match {
        case e: ScInfixExpr if e.lOp.getText == LIBRARY_DEPENDENCIES && isAddableLibraryDependencies(e) => res ++= Seq(e)
        case call: ScMethodCall if call.deepestInvokedExpr.getText == SEQ => res ++= Seq(call)
        case typedSeq: ScTypedStmt if typedSeq.isSequenceArg =>
          typedSeq.expr match {
            case call: ScMethodCall if call.deepestInvokedExpr.getText == SEQ => res ++= Seq(typedSeq)
            case _ =>
          }
        case settings: ScMethodCall if isAddableSettings(settings) =>
          settings.getEffectiveInvokedExpr match {
            case expr: ScReferenceExpression if expr.refName == SETTINGS => res ++= Seq(settings)
            case _ =>
          }
        case _ =>
      }
    }

    processPatternDefinition(proj)(action)

    res
  }

  def getTopLevelSbtProjects(psiSbtFile: ScalaFile): Seq[ScPatternDefinition] = {
    var res: Seq[ScPatternDefinition] = List()

    psiSbtFile.acceptChildren(new ScalaElementVisitor {
      override def visitPatternDefinition(pat: ScPatternDefinition): Unit = {
        if (pat.expr.isEmpty)
          return

        if (pat.expr.get.getType().get.canonicalText != SBT_PROJECT_TYPE)
          return

        res = res ++ Seq(pat)
        super.visitPatternDefinition(pat)
      }
    })

    res
  }

  def getTopLevelLibraryDependencies(psiSbtFile: ScalaFile): Seq[ScInfixExpr] = {
    var res: Seq[ScInfixExpr] = List()

    psiSbtFile.acceptChildren(new ScalaElementVisitor {
      override def visitInfixExpression(infix: ScInfixExpr): Unit = {
        if (infix.lOp.getText == LIBRARY_DEPENDENCIES && infix.getParent.isInstanceOf[PsiFile]) {
          res = res ++ Seq(infix)
        }
      }
    })

    res
  }

  def getTopLevelPlaceToAdd(psiFile: ScalaFile)(implicit project: Project): DependencyPlaceInfo = {
    val line: Int = StringUtil.offsetToLineNumber(psiFile.getText, psiFile.getTextLength) + 1
    DependencyPlaceInfo(getRelativePath(psiFile), psiFile.getTextLength, line, psiFile, Seq())
  }

  def addDependency(expr: PsiElement, info: ArtifactInfo)(implicit project: Project): PsiElement = {
    expr match {
      case e: ScInfixExpr if e.lOp.getText == LIBRARY_DEPENDENCIES => addDependencyToLibraryDependencies(e, info)
      case call: ScMethodCall if call.deepestInvokedExpr.getText == SEQ => addDependencyToSeq(call, info)
      case typedSeq: ScTypedStmt if typedSeq.isSequenceArg => addDependencyToTypedSeq(typedSeq, info)
      case settings: ScMethodCall if isAddableSettings(settings) =>
        settings.getEffectiveInvokedExpr match {
          case expr: ScReferenceExpression if expr.refName == SETTINGS =>
            addDependencyToSettings(settings, info)(project)
          case _ => null
        }
      case file: PsiFile => addDependencyToFile(file, info)(project)
      case _ => null
    }
  }

  def addDependencyToLibraryDependencies(infix: ScInfixExpr, info: ArtifactInfo)(implicit project: Project): PsiElement = {
    var res: PsiElement = null
    val psiFile = infix.getContainingFile
    val opName = infix.operation.refName

    if (opName == "+=") {
      val dependency: ScExpression = infix.rOp
      val seqCall: ScMethodCall = generateSeqPsiMethodCall(info)(project)

      doInSbtWriteCommandAction({
        seqCall.args.addExpr(dependency.copy().asInstanceOf[ScExpression])
        seqCall.args.addExpr(generateArtifactPsiExpression(info)(project))
        infix.operation.replace(ScalaPsiElementFactory.createElementFromText("++=")(project))
        dependency.replace(seqCall)
      }, psiFile)(project)

      res = infix.rOp
    } else if (opName == "++=") {
      val dependencies: ScExpression = infix.rOp
      dependencies match {
        case call: ScMethodCall =>
          val text = call.deepestInvokedExpr.getText
          if (text == SEQ) {
            val addedExpr = generateArtifactPsiExpression(info)(project)
            doInSbtWriteCommandAction({
              call.args.addExpr(addedExpr)
            }, psiFile)(project)

            res = addedExpr
          }
        case _ =>
      }
    }

    res
  }

  def addDependencyToSeq(seqCall: ScMethodCall, info: ArtifactInfo)(implicit project: Project): PsiElement = {
    val formalSeq: ScType = ScalaPsiElementFactory.createTypeFromText(SBT_SEQ_TYPE, seqCall, seqCall).get
    val formalSetting: ScType = ScalaPsiElementFactory.createTypeFromText(SBT_SETTING_TYPE, seqCall, seqCall).get

    seqCall.getType().get match {
      case parameterized: ScParameterizedType if parameterized.designator.equiv(formalSeq) =>
        val args = parameterized.typeArguments
        if (args.length == 1) {
          args.head match {
            case parameterized: ScParameterizedType if parameterized.designator.equiv(formalSetting) =>
              val addedExpr: ScInfixExpr = generateLibraryDependency(info)
              doInSbtWriteCommandAction({
                seqCall.args.addExpr(addedExpr)
              }, seqCall.getContainingFile)
              return addedExpr
            case _ =>
          }
        }
    }

    val addedExpr = generateArtifactPsiExpression(info)
    doInSbtWriteCommandAction({
      seqCall.args.addExpr(addedExpr)
    }, seqCall.getContainingFile)

    addedExpr
  }

  def addDependencyToTypedSeq(typedSeq: ScTypedStmt, info: ArtifactInfo)(implicit project: Project): PsiElement = {
    typedSeq.expr match {
      case seqCall: ScMethodCall =>
        val addedExpr = generateLibraryDependency(info)(project)
        doInSbtWriteCommandAction({
          seqCall.args.addExpr(addedExpr)
        }, seqCall.getContainingFile)
        addedExpr
      case _ => null
    }
  }

  def addDependencyToFile(file: PsiFile, info: ArtifactInfo)(implicit project: Project): PsiElement = {
    var addedExpr: PsiElement = null
    doInSbtWriteCommandAction({
      file.addAfter(generateNewLine(project), file.getLastChild)
      addedExpr = file.addAfter(generateLibraryDependency(info), file.getLastChild)
    }, file)
    addedExpr
  }

  def addDependencyToSettings(settings: ScMethodCall, info: ArtifactInfo)(implicit project: Project): PsiElement = {
    val addedExpr = generateLibraryDependency(info)(project)
    doInSbtWriteCommandAction({
      settings.args.addExpr(addedExpr)
    }, settings.getContainingFile)(project)
    addedExpr
  }

  def isAddableSettings(settings: ScMethodCall): Boolean = {
    val args = settings.args.exprsArray

    if (args.length == 1) {
      args(0) match {
        case typedStmt: ScTypedStmt if typedStmt.isSequenceArg =>
          typedStmt.expr match {
            case _: ScMethodCall => false
            case _: ScReferenceExpression => false
            case _ => true
          }
        case _ => true
      }
    } else {
      true
    }
  }

  def isAddableLibraryDependencies(libDeps: ScInfixExpr): Boolean = {
    if (libDeps.operation.refName == "+=") {
      return true
    } else if (libDeps.operation.refName == "++=") {
      libDeps.rOp match {
        // In this case we return false, because of not to repeat it several times
        case call: ScMethodCall if call.deepestInvokedExpr.getText == SEQ => return false
        case _ =>
      }
    }

    false
  }

  private def doInSbtWriteCommandAction(f: => Unit, psiSbtFile: PsiFile)(implicit project: ProjectContext): Unit = {
    new WriteCommandAction[Unit](project, psiSbtFile) {
      override def run(result: Result[Unit]): Unit = {
        f
      }
    }.execute()
  }

  private def generateSeqPsiMethodCall(info: ArtifactInfo)(implicit ctx: ProjectContext): ScMethodCall =
    ScalaPsiElementFactory.createElementFromText(s"$SEQ()").asInstanceOf[ScMethodCall]

  private def generateLibraryDependency(info: ArtifactInfo)(implicit ctx: ProjectContext): ScInfixExpr =
    ScalaPsiElementFactory.createElementFromText(s"$LIBRARY_DEPENDENCIES += ${generateArtifactText(info)}").asInstanceOf[ScInfixExpr]

  private def generateArtifactPsiExpression(info: ArtifactInfo)(implicit ctx: ProjectContext): ScExpression =
    ScalaPsiElementFactory.createElementFromText(generateArtifactText(info))(ctx).asInstanceOf[ScExpression]

  private def generateNewLine(implicit ctx: ProjectContext): PsiElement = ScalaPsiElementFactory.createElementFromText("\n")

  private def generateArtifactText(info: ArtifactInfo): String =
    "\"" + s"${info.groupId}" + "\" % \"" + s"${info.artifactId}" + "\" % \"" + s"${info.version}" + "\""

  def getRelativePath(elem: PsiElement)(implicit project: Project): String = {
    val path = elem.getContainingFile.getVirtualFile.getCanonicalPath
    if (!path.startsWith(project.getBasePath))
      return null

    path.substring(project.getBasePath.length + 1)
  }

  def toDependencyPlaceInfo(elem: PsiElement, affectedProjects: Seq[String])(implicit project: Project): DependencyPlaceInfo = {
    val offset =
      elem match {
        case call: ScMethodCall =>
          call.getEffectiveInvokedExpr match {
            case expr: ScReferenceExpression => expr.nameId.getTextOffset
            case _ => elem.getTextOffset
          }
        case _ => elem.getTextOffset
      }

    val line: Int = StringUtil.offsetToLineNumber(elem.getContainingFile.getText, offset) + 1

    DependencyPlaceInfo(getRelativePath(elem), offset, line, elem, affectedProjects)
  }
}