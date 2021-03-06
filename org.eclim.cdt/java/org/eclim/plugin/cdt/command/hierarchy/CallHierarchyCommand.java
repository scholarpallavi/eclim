/**
 * Copyright (C) 2005 - 2013  Eric Van Dewoestine
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.eclim.plugin.cdt.command.hierarchy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import java.text.Collator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.eclim.annotation.Command;

import org.eclim.command.CommandLine;
import org.eclim.command.Options;

import org.eclim.plugin.cdt.command.search.SearchCommand;

import org.eclim.plugin.cdt.util.CUtils;

import org.eclim.util.StringUtils;

import org.eclim.util.file.Position;

import org.eclipse.cdt.core.CCorePlugin;

import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.IBinding;

import org.eclipse.cdt.core.dom.ast.cpp.ICPPMethod;

import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexBinding;
import org.eclipse.cdt.core.index.IIndexManager;
import org.eclipse.cdt.core.index.IIndexName;

import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.model.IFunction;
import org.eclipse.cdt.core.model.IFunctionDeclaration;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.cdt.core.model.IWorkingCopy;

import org.eclipse.cdt.internal.core.dom.parser.cpp.ClassTypeHelper;

import org.eclipse.cdt.internal.core.model.ASTCache;

import org.eclipse.cdt.internal.ui.callhierarchy.CallHierarchyUI;

import org.eclipse.cdt.internal.ui.editor.ASTProvider;
import org.eclipse.cdt.internal.ui.editor.WorkingCopyManager;

import org.eclipse.cdt.internal.ui.viewsupport.IndexUI;

import org.eclipse.cdt.ui.CUIPlugin;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;

import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;

import org.eclipse.ui.IEditorInput;

import org.eclipse.ui.part.FileEditorInput;

/**
 * Command to generate a call hierarchy for a method or function.
 *
 * @author Eric Van Dewoestine
 */
@Command(
  name = "c_callhierarchy",
  options =
    "REQUIRED p project ARG," +
    "REQUIRED f file ARG," +
    "REQUIRED o offset ARG," +
    "REQUIRED l length ARG," +
    "REQUIRED e encoding ARG," +
    "OPTIONAL c callees NOARG"
)
public class CallHierarchyCommand
  extends SearchCommand
{
  private static final String CALLEES_OPTION = "c";

  @Override
  public Object execute(CommandLine commandLine)
    throws Exception
  {
    String projectName = commandLine.getValue(Options.PROJECT_OPTION);
    String file = commandLine.getValue(Options.FILE_OPTION);
    boolean callees = commandLine.hasOption(CALLEES_OPTION);
    int offset = getOffset(commandLine);
    int length = commandLine.getIntValue(Options.LENGTH_OPTION);

    ICProject cproject = CUtils.getCProject(projectName);

    CUIPlugin cuiPlugin = CUIPlugin.getDefault();
    ITranslationUnit src = CUtils.getTranslationUnit(cproject, file);
    CCorePlugin.getIndexManager().update(
        new ICElement[]{src}, IIndexManager.UPDATE_ALL);
    CCorePlugin.getIndexManager().joinIndexer(3000, new NullProgressMonitor());
    src = src.getWorkingCopy();

    IEditorInput input = new FileEditorInput((IFile)src.getResource());

    // hack... there has to be a better way
    WorkingCopyManager manager = (WorkingCopyManager)
      cuiPlugin.getWorkingCopyManager();
    manager.connect(input);
    manager.setWorkingCopy(input, (IWorkingCopy)src);

    HashMap<String,Object> result = new HashMap<String,Object>();
    try{
      // more hacks to got get around gui dependency
      ASTProvider provider = ASTProvider.getASTProvider();
      Field astCache = ASTProvider.class.getDeclaredField("fCache");
      astCache.setAccessible(true);
      ((ASTCache)astCache.get(provider)).setActiveElement(src);

      TextSelection selection = new TextSelection(offset, length);
      Method findDefinitions = CallHierarchyUI.class.getDeclaredMethod(
          "findDefinitions",
          ICProject.class, IEditorInput.class, ITextSelection.class);
      findDefinitions.setAccessible(true);
      ICElement[] elements = (ICElement[])findDefinitions.invoke(
          null, cproject, input, selection);

      if (elements != null && elements.length > 0) {
        ICElement element = elements[0];
        Set<ICElement> seen = new HashSet<ICElement>();
        ICProject project = element.getCProject();
        ICProject[] scope = getScope(SCOPE_PROJECT, project);
        IIndex index = CCorePlugin.getIndexManager().getIndex(
            scope, IIndexManager.ADD_DEPENDENCIES | IIndexManager.ADD_DEPENDENT);
        index.acquireReadLock();
        try{
          IIndexName name = IndexUI.elementToName(index, element);
          result = formatElement(index, new Call(name, element), seen, callees);
        }finally{
          index.releaseReadLock();
        }
      }
    }finally{
      manager.removeWorkingCopy(input);
      manager.disconnect(input);
    }

    return result;
  }

  private ArrayList<HashMap<String,Object>> findCallers(
      IIndex index, ICElement element, Set<ICElement> seen)
    throws Exception
  {
    ArrayList<HashMap<String,Object>> results =
      new ArrayList<HashMap<String,Object>>();
    ICProject project = element.getCProject();
    IIndexBinding calleeBinding = IndexUI.elementToBinding(index, element);
    if (calleeBinding != null) {
      results.addAll(findCallers(index, calleeBinding, true, project, seen));
      if (calleeBinding instanceof ICPPMethod) {
        // cdt 8.1.1 requires a second arg (point: IASTNode), but cdt 8.1.1
        // hasn't been released independent of eclipse 4.2.1, so distros are
        // less likely to have it. So rather than attempt to force an
        // eclipse/cdt version, we'll resort to reflection for now this time.
        /*IBinding[] overriddenBindings =
          ClassTypeHelper.findOverridden((ICPPMethod)calleeBinding, null);*/
        IBinding[] overriddenBindings = null;
        try{
          Method findOverridden = ClassTypeHelper.class
            .getMethod("findOverridden", ICPPMethod.class, IASTNode.class);
          overriddenBindings = (IBinding[])
            findOverridden.invoke(null, (ICPPMethod)calleeBinding, null);
        }catch(NoSuchMethodException nsme){
          Method findOverridden = ClassTypeHelper.class
            .getMethod("findOverridden", ICPPMethod.class);
          overriddenBindings = (IBinding[])
            findOverridden.invoke(null, (ICPPMethod)calleeBinding);
        }

        for (IBinding overriddenBinding : overriddenBindings) {
          results.addAll(findCallers(
              index, overriddenBinding, false, project, seen));
        }
      }
    }
    return results;
  }

  private ArrayList<HashMap<String,Object>> findCallers(
      IIndex index,
      IBinding binding,
      boolean includeOrdinaryCalls,
      ICProject project,
      Set<ICElement> seen)
    throws Exception
  {
    IIndexName[] names = index.findNames(
        binding, IIndex.FIND_REFERENCES | IIndex.SEARCH_ACROSS_LANGUAGE_BOUNDARIES);

    ArrayList<Call> calls = new ArrayList<Call>(names.length);
    for (IIndexName name : names) {
      if (includeOrdinaryCalls || name.couldBePolymorphicMethodCall()) {
        IIndexName caller = name.getEnclosingDefinition();
        if (caller == null) {
          continue;
        }

        ICElement element = IndexUI.getCElementForName(project, index, caller);
        if (element == null) {
          continue;
        }
        calls.add(new Call(name, element));
      }
    }

    Collections.sort(calls);

    ArrayList<HashMap<String,Object>> results =
      new ArrayList<HashMap<String,Object>>();
    for (Call call : calls) {
      results.add(formatElement(index, call, seen, false));
    }
    return results;
  }

  private ArrayList<HashMap<String,Object>> findCallees(
      IIndex index, ICElement element, Set<ICElement> seen)
      throws Exception
  {
    ICProject project = element.getCProject();
    IIndexName name = IndexUI.elementToName(index, element);
    IIndexName[] enclosedNames = name.getEnclosedNames();

    ArrayList<Call> calls = new ArrayList<Call>(enclosedNames.length);
    for (IIndexName enclosedName : enclosedNames) {
      IIndexBinding binding = index.findBinding(enclosedName);
      IIndexName[] enclosedDefinitionNames = index.findDefinitions(binding);
      if (enclosedDefinitionNames == null || enclosedDefinitionNames.length == 0){
        continue;
      }

      IIndexName enclosedDefinitionName = enclosedDefinitionNames[0];
      ICElement enclosedElement =
        IndexUI.getCElementForName(project, index, enclosedDefinitionName);
      if (enclosedElement == null) {
        continue;
      }

      if (enclosedElement instanceof IFunction) {
        calls.add(new Call(
              enclosedName, enclosedElement, element.getResource()));
      }
    }

    Collections.sort(calls);

    ArrayList<HashMap<String,Object>> results =
      new ArrayList<HashMap<String,Object>>();
    for (Call call : calls) {
      results.add(formatElement(index, call, seen, true));
    }
    return results;
  }

  private HashMap<String,Object> formatElement(
      IIndex index,
      Call call,
      Set<ICElement> seen,
      boolean callees)
    throws Exception
  {
    HashMap<String,Object> result = new HashMap<String,Object>();

    String[] types = null;
    IIndexName name = call.name;
    ICElement element = call.element;

    if (element instanceof IFunction){
      types = ((IFunction)element).getParameterTypes();
    }else if (element instanceof IFunctionDeclaration){
      types = ((IFunctionDeclaration)element).getParameterTypes();
    }
    String message = element.getElementName() +
      '(' + StringUtils.join(types, ", ") + ')';
    result.put("name", message);

    if (name != null){
      IResource resource = call.resource;
      if (resource != null){
        String file = resource.getLocation().toOSString().replace('\\', '/');
        result.put("position",
            Position.fromOffset(file, null, name.getNodeOffset(), 0));
      }
    }

    if (!seen.contains(element)){
      seen.add(element);
      if (callees) {
        result.put("callees", findCallees(index, element, seen));
      } else {
        result.put("callers", findCallers(index, element, seen));
      }
    }

    return result;
  }

  private static class Call
    implements Comparable<Call>
  {
    private static final Collator COLLATOR = Collator.getInstance();

    public IIndexName name;
    public ICElement element;
    public IResource resource;
    public String location;

    public Call(IIndexName name, ICElement element)
    {
      this.name = name;
      this.element = element;
      this.resource = element.getResource();
      this.location =
        element.getResource().getLocation().toOSString() +
        element.getElementName();
    }

    public Call(IIndexName name, ICElement element, IResource resource)
    {
      this(name, element);
      this.resource = resource;
    }

    @Override
    public int compareTo(Call o)
    {
      int result = COLLATOR.compare(location, o.location);
      if (result == 0){
        return name.getNodeOffset() - o.name.getNodeOffset();
      }
      return result;
    }
  }
}
