package org.jetbrains.plugins.scala.gotoclass;

import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.scala.finder.ScalaSourceFilterScope;
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction;
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAlias;
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValue;
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable;
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter;
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement;
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody;
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys;
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings;

import java.util.ArrayList;
import java.util.Collection;

/**
 * User: Alexander Podkhalyuzin
 * Date: 14.10.2008
 */
public class ScalaGoToSymbolContributor implements ChooseByNameContributor {
  public String[] getNames(Project project, boolean includeNonProjectItems) {
    final Collection<String> items = StubIndex.getInstance().getAllKeys(ScalaIndexKeys.METHOD_NAME_KEY(), project);
    items.addAll(StubIndex.getInstance().getAllKeys(ScalaIndexKeys.VALUE_NAME_KEY(), project));
    items.addAll(StubIndex.getInstance().getAllKeys(ScalaIndexKeys.VARIABLE_NAME_KEY(), project));
    items.addAll(StubIndex.getInstance().getAllKeys(ScalaIndexKeys.CLASS_PARAMETER_NAME_KEY(), project));
    items.addAll(StubIndex.getInstance().getAllKeys(ScalaIndexKeys.TYPE_ALIAS_NAME_KEY(), project));
    return items.toArray(new String[items.size()]);
  }

  @NotNull
  public NavigationItem[] getItemsByName(String name, String pattern, Project project, boolean includeNonProjectItems) {
    final boolean searchAll = ScalaProjectSettings.getInstance(project).isSearchAllSymbols();
    final GlobalSearchScope scope = includeNonProjectItems ? GlobalSearchScope.allScope(project) : GlobalSearchScope.projectScope(project);
    final Collection<ScFunction> methods =
        StubIndex.getInstance().safeGet(ScalaIndexKeys.METHOD_NAME_KEY(), name, project,
            new ScalaSourceFilterScope(scope, project), ScFunction.class);
    final Collection<ScTypeAlias> types =
        StubIndex.getInstance().safeGet(ScalaIndexKeys.TYPE_ALIAS_NAME_KEY(), name, project,
            new ScalaSourceFilterScope(scope, project), ScTypeAlias.class);
    final Collection<ScValue> values =
        StubIndex.getInstance().safeGet(ScalaIndexKeys.VALUE_NAME_KEY(), name, project,
            new ScalaSourceFilterScope(scope, project), ScValue.class);
    final Collection<ScVariable> vars =
        StubIndex.getInstance().safeGet(ScalaIndexKeys.VARIABLE_NAME_KEY(), name, project,
            new ScalaSourceFilterScope(scope, project), ScVariable.class);
    final Collection<ScClassParameter> params =
        StubIndex.getInstance().safeGet(ScalaIndexKeys.CLASS_PARAMETER_NAME_KEY(), name, project,
            new ScalaSourceFilterScope(scope, project), ScClassParameter.class);

    final ArrayList<NavigationItem> items = new ArrayList<NavigationItem>();

    if (searchAll) {
      items.addAll(methods);
      items.addAll(types);
    }
    else {
      for (ScFunction method : methods) {
        if (!isLocal(method)) items.add(method);
      }
      for (ScTypeAlias type : types) {
        if (!isLocal(type)) items.add(type);
      }
    }
    for (ScValue value : values) {
      if (!isLocal(value) || searchAll) {
        final PsiNamedElement[] elems = value.declaredElementsArray();
        for (PsiNamedElement elem : elems) {
          if (elem instanceof NavigationItem) {
            final NavigationItem navigationItem = (NavigationItem) elem;
            String navigationItemName = navigationItem.getName();
            if (navigationItem instanceof ScNamedElement) navigationItemName = ((ScNamedElement) navigationItem).name();
            if (name.equals(navigationItemName)) items.add(navigationItem);
          }
        }
      }
    }

    for (ScVariable var : vars) {
      if (!isLocal(var) || searchAll) {
        final PsiNamedElement[] elems = var.declaredElementsArray();
        for (PsiNamedElement elem : elems) {
          final NavigationItem navigationItem = (NavigationItem) elem;
          String navigationItemName = navigationItem.getName();
          if (navigationItem instanceof ScNamedElement) navigationItemName = ((ScNamedElement) navigationItem).name();
          if (name.equals(navigationItemName)) items.add(navigationItem);
        }
      }
    }

    items.addAll(params);

    return items.toArray(new NavigationItem[items.size()]);
  }

  private boolean isLocal(NavigationItem item) {
    if (item instanceof PsiElement) {
      PsiElement element = (PsiElement) item;
      if (element.getParent() instanceof ScTemplateBody) {
        return false;
      }
    }
    return true;
  }
}
