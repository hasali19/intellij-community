// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.dom.productModules;

import com.intellij.util.xml.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.devkit.dom.impl.productModules.IntellijModuleConverter;
import org.jetbrains.idea.devkit.symbols.IntellijModuleSymbol;

import java.util.List;

@DefinesXml
public interface ProductModulesElement extends DomElement {
  @NotNull
  List<IncludedElement> getIncludes();

  @SubTag
  @Nullable MainRootModuleElements getMainRootModules();

  @SubTag
  @Nullable BundledPluginElements getBundledPlugins();
  
  interface IncludedElement extends DomElement {
    @SubTag("from-module")
    @Required
    @Convert(IntellijModuleConverter.class)
    @NotNull GenericDomValue<IntellijModuleSymbol> getIncludedModule();
  }

  interface MainRootModuleElements extends DomElement {
    @SubTagList
    @NotNull List<MainRootModuleElement> getModules();
  }
  
  @Convert(IntellijModuleConverter.class)
  interface MainRootModuleElement extends GenericDomValue<IntellijModuleSymbol> {
    @Required
    @NotNull GenericAttributeValue<ModuleImportanceAttributeValue> getImportance();
  }

  interface BundledPluginElements extends DomElement {
    @SubTagList
    @NotNull List<BundledPluginElement> getModules();
  }
  
  @Convert(IntellijModuleConverter.class)
  interface BundledPluginElement extends GenericDomValue<IntellijModuleSymbol> {
  }
  
  enum ModuleImportanceAttributeValue {
    functional,
    optional,
    service
  }
}
