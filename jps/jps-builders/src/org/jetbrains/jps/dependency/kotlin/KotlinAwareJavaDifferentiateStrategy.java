// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.dependency.kotlin;

import com.intellij.openapi.util.Pair;
import com.intellij.util.SmartList;
import com.intellij.util.containers.SmartHashSet;
import kotlinx.metadata.*;
import kotlinx.metadata.jvm.JvmExtensionsKt;
import kotlinx.metadata.jvm.JvmMethodSignature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.dependency.DifferentiateContext;
import org.jetbrains.jps.dependency.Node;
import org.jetbrains.jps.dependency.diff.Difference;
import org.jetbrains.jps.dependency.java.*;

import java.util.*;
import java.util.function.Predicate;

import static org.jetbrains.jps.javac.Iterators.*;

/**
 * This strategy augments Java strategy with some Kotlin-specific rules. Should be used in projects containing both Java and Kotlin code.
 */
public final class KotlinAwareJavaDifferentiateStrategy extends JvmDifferentiateStrategyImpl {
  private static final TypeRepr.ClassType JVM_OVERLOADS_ANNOTATION = new TypeRepr.ClassType("kotlin/jvm/JvmOverloads");
  @Override
  public boolean processAddedClass(DifferentiateContext context, JvmClass addedClass, Utils future, Utils present) {
    for (JvmClass superClass : filter(future.allDirectSupertypes(addedClass), KotlinAwareJavaDifferentiateStrategy::isSealed)) {
      affectNodeSources(context, superClass.getReferenceID(), "Subclass of a sealed class was added, affecting ");
    }

    if (!addedClass.isPrivate()) {
      // calls to newly added class' constructors may shadow calls to functions named similarly
      debug("Affecting lookup usages for added class ", addedClass.getName());
      affectClassLookupUsages(context, addedClass);
    }

    return true;
  }

  @Override
  public boolean processRemovedClass(DifferentiateContext context, JvmClass removedClass, Utils future, Utils present) {
    for (JvmClass superClass : filter(future.allDirectSupertypes(removedClass), KotlinAwareJavaDifferentiateStrategy::isSealed)) {
      affectNodeSources(context, superClass.getReferenceID(), "Subclass of a sealed class was removed, affecting ");
    }

    if (!removedClass.isInnerClass()) {
      // this will affect all imports of this class in kotlin sources
      KmDeclarationContainer container = getDeclarationContainer(removedClass);
      if (container == null /*is non-kotlin node*/ || container instanceof KmClass) {
        debug("Affecting lookup usages for removed class ", removedClass.getName());
        affectClassLookupUsages(context, removedClass);
      }
    }

    for (KmFunction kmFunction : allKmFunctions(removedClass)) {
      if (Attributes.isInline(kmFunction)) {
        debug("Function in a removed class was inlineable, affecting method usages ", kmFunction.getName());
        affectMemberLookupUsages(context, removedClass, kmFunction.getName(), present);
      }
    }
    return true;
  }

  @Override
  public boolean processChangedClass(DifferentiateContext context, Difference.Change<JvmClass, JvmClass.Diff> change, Utils future, Utils present) {
    JvmClass changedClass = change.getPast();
    JvmClass.Diff diff = change.getDiff();
    Iterable<JvmMethod> removedMethods = diff.methods().removed();
    Iterable<JvmField> addedNonPrivateFields = filter(diff.fields().added(), f -> !f.isPrivate());
    Iterable<JvmField> exposedFields = filter(map(diff.fields().changed(), ch -> ch.getDiff().accessExpanded()? ch.getPast() : null), Objects::nonNull);

    if (isKotlinNode(changedClass) && (diff.superClassChanged() || !diff.interfaces().unchanged())) {
      Difference.Specifier<JvmNodeReferenceID, ?> sealedDiff = Difference.diff(
        map(filter(present.allDirectSupertypes(change.getPast()), KotlinAwareJavaDifferentiateStrategy::isSealed), JVMClassNode::getReferenceID),
        map(filter(future.allDirectSupertypes(change.getNow()), KotlinAwareJavaDifferentiateStrategy::isSealed), JVMClassNode::getReferenceID)
      );
      for (JvmNodeReferenceID id : sealedDiff.added()) {
        affectNodeSources(context, id, "Subclass of a sealed class was added, affecting ");
      }
      for (JvmNodeReferenceID id : sealedDiff.removed()) {
        affectNodeSources(context, id, "Subclass of a sealed class was removed, affecting ");
      }
    }

    if (!isEmpty(removedMethods) || !isEmpty(addedNonPrivateFields) || !isEmpty(exposedFields)) {
      for (PropertyDescriptor property : findProperties(changedClass)) {

        // KT-46743 Incremental compilation doesn't process usages of Java property in Kotlin code if getter is removed
        for (JvmMethod removedMethod : removedMethods) {
          if (removedMethod.isSame(property.getter) && property.setter != null) {
            debug("Kotlin interop: a property getter ", removedMethod.getName(), " was removed => affecting usages of corresponding setter ", property.setter.getName());
            affectMemberUsages(context, changedClass.getReferenceID(), property.setter, future.collectSubclassesWithoutMethod(changedClass.getReferenceID(), property.setter));
            break;
          }
        }

        // KT-55393 JPS: Java synthetic properties incremental compilation is broken
        for (JvmField field : flat(addedNonPrivateFields, exposedFields)) {
          if (Objects.equals(field.getName(), property.getter.getName()) || property.name.equalsIgnoreCase(field.getName())) {
            debug("Kotlin interop: a non-private field with name ", field.getName(), " was added, or the field became more accessible");
            debug(" => affecting usages of corresponding property getter ", property.getter.getName());
            affectMemberUsages(context, changedClass.getReferenceID(), property.getter, future.collectSubclassesWithoutMethod(changedClass.getReferenceID(), property.getter));
            if (property.setter != null) {
              debug(" => affecting usages of corresponding property setter ", property.setter.getName());
              affectMemberUsages(context, changedClass.getReferenceID(), property.setter, future.collectSubclassesWithoutMethod(changedClass.getReferenceID(), property.setter));
            }
            break;
          }
        }
      }
    }

    if (!present.isLambdaTarget(change.getPast()) && future.isLambdaTarget(change.getNow())) {
      // should affect lambda instantiations on overloads, because some calls may have become ambiguous
      TypeRepr.ClassType samType = new TypeRepr.ClassType(changedClass.getName());
      for (JvmClass depClass : flat(map(context.getGraph().getDependingNodes(changedClass.getReferenceID()), dep -> present.getNodes(dep, JvmClass.class)))) {
        for (JvmMethod methodWithSAMType : filter(depClass.getMethods(), m -> contains(m.getArgTypes(), samType))) {
          affectConflictingCallExpressions(context, depClass, methodWithSAMType, present, null);
        }
      }
    }

    for (Difference.Change<KotlinMeta, KotlinMeta.Diff> metaChange : diff.metadata(KotlinMeta.class).changed()) {
      KotlinMeta.Diff metaDiff = metaChange.getDiff();

      if (metaDiff.typeParametersVarianceChanged()) {
        debug("Kotlin class' type parameters' variance changed; affecting class usage ", changedClass.getName());
        affectSubclasses(context, future, change.getNow().getReferenceID(), true);
      }

      for (KmFunction removedFunction : metaDiff.functions().removed()) {
        JvmMethod method = getJvmMethod(change.getNow(), JvmExtensionsKt.getSignature(removedFunction));
        if (method != null && !method.isPrivate()) {
          // a function in kotlin code was replaced with a property, but at the bytecode level corresponding methods are preserved
          for (JvmClass subClass : filter(flat(map(future.allSubclasses(changedClass.getReferenceID()), id -> future.getNodes(id, JvmClass.class))), n -> isKotlinNode(n))) {
            if (find(subClass.getMethods(), m -> !m.isPrivate() && method.isSameByJavaRules(m)) != null) {
              affectNodeSources(context, subClass.getReferenceID(), "Kotlin function " + removedFunction.getName() + " has been removed. Affecting corresponding method in subclasses: ");
            }
          }
        }
      }

      for (Difference.Change<KmFunction, KotlinMeta.KmFunctionsDiff> funChange : metaDiff.functions().changed()) {
        KmFunction changedKmFunction = funChange.getPast();
        Visibility visibility = Attributes.getVisibility(changedKmFunction);
        if (visibility == Visibility.PRIVATE || visibility == Visibility.PRIVATE_TO_THIS) {
          continue;
        }
        KotlinMeta.KmFunctionsDiff funDiff = funChange.getDiff();
        if (funDiff.becameNullable() || funDiff.argsBecameNotNull()) {
          debug("One of method's parameters or method's return value has become non-nullable ", changedKmFunction.getName());
          JvmMethod jvmMethod = getJvmMethod(changedClass, JvmExtensionsKt.getSignature(changedKmFunction));
          if (jvmMethod != null) {
            // this will affect all usages from both java and kotlin code
            for (JvmMethod method : withJvmOverloads(changedClass, jvmMethod)) {
              for (Pair<JvmClass, JvmMethod> pair : future.getOverridingMethods(changedClass, method, method::isSameByJavaRules)) {
                affectNodeSources(context, pair.getFirst().getReferenceID(), "Affect class where the function is overridden: ");
              }
              affectMemberUsages(context, changedClass.getReferenceID(), method, future.collectSubclassesWithoutMethod(changedClass.getReferenceID(), method));
            }
          }
          if (isDeclaresDefaultValue(changedKmFunction)) {
            // additionally: functions with default parameters produce several methods in bytecode, so need to affect by lookup usage
            debug("One of method's parameters or method's return value has become non-nullable; or function's receiver parameter changed: ", changedKmFunction.getName());
            affectMemberLookupUsages(context, changedClass, changedKmFunction.getName(), future);
          }
        }
        if (funDiff.receiverParameterChanged()) {
          debug("Function's receiver parameter changed: ", changedKmFunction.getName());
          affectMemberLookupUsages(context, changedClass, changedKmFunction.getName(), future);
        }
      }

      for (KmProperty removedProp : metaDiff.properties().removed()) {
        List<JvmMethodSignature> propertyAccessors = Arrays.asList(JvmExtensionsKt.getGetterSignature(removedProp), JvmExtensionsKt.getSetterSignature(removedProp));
        List<JvmMethod> accessorMethods = collect(filter(map(propertyAccessors, acc -> acc != null? getJvmMethod(change.getNow(), acc) : null), m -> m != null && !m.isPrivate()), new SmartList<>());

        if (!accessorMethods.isEmpty()) {
          // property in kotlin code was replaced with a function(s), but at the bytecode level corresponding methods are preserved
          for (JvmClass subClass : filter(flat(map(future.allSubclasses(changedClass.getReferenceID()), id -> future.getNodes(id, JvmClass.class))), n -> isKotlinNode(n))) {
            if (find(subClass.getMethods(), m -> !m.isPrivate() && find(accessorMethods, m::isSameByJavaRules) != null) != null) {
              affectNodeSources(context, subClass.getReferenceID(), "Kotlin property " + removedProp.getName() + " has been removed. Affecting corresponding accessor method(s) in subclasses: ");
            }
          }
        }
      }

      for (Difference.Change<KmProperty, KotlinMeta.KmPropertiesDiff> propChange : metaDiff.properties().changed()) {
        KmProperty prop = propChange.getPast();
        KotlinMeta.KmPropertiesDiff propDiff = propChange.getDiff();
        if (propDiff.becameNullable()) {
          JvmMethod getter = getJvmMethod(changedClass, JvmExtensionsKt.getGetterSignature(prop));
          if (getter != null && !getter.getFlags().isPrivate()) {
            debug("A property has become nullable; affecting getter usages ", getter);
            affectMemberUsages(context, changedClass.getReferenceID(), getter, future.collectSubclassesWithoutMethod(changedClass.getReferenceID(), getter));
          }
        }
        else if (propDiff.becameNotNull()) {
          JvmMethod setter = getJvmMethod(changedClass, JvmExtensionsKt.getSetterSignature(prop));
          if (setter != null && !setter.getFlags().isPrivate()) {
            debug("A property has become not-null; affecting setter usages ", setter);
            affectMemberUsages(context, changedClass.getReferenceID(), setter, future.collectSubclassesWithoutMethod(changedClass.getReferenceID(), setter));
          }
        }
      }
    }

    return true;
  }

  private static boolean isSealed(JvmClass cls) {
    KmDeclarationContainer container = getDeclarationContainer(cls);
    return container instanceof KmClass && Attributes.getModality(((KmClass)container)) == Modality.SEALED;
  }

  @Override
  public boolean processAddedMethod(DifferentiateContext context, Difference.Change<JvmClass, JvmClass.Diff> change, JvmMethod addedMethod, Utils future, Utils present) {
    JvmClass changedClass = change.getNow();

    // any added method may conflict with an extension method to this class, defined elsewhere
    MethodUsage addedMethodUsage = addedMethod.createUsage(changedClass.getReferenceID());
    // Do not affect nodes that already use this method. Since the method is just added, already existing usage in some node means the node has been already compiled against the most recent version of this class
    affectConflictingCallExpressions(context, changedClass, addedMethod, future, n -> !contains(n.getUsages(), addedMethodUsage));

    if (!changedClass.isPrivate() && "invoke".equals(addedMethod.getName())) {
      KmFunction kmFunction = getKmFunction(changedClass, addedMethod);
      if (kmFunction != null && Attributes.isOperator(kmFunction)) {
        debug("Operator method invoke() has been added. Affecting classes instantiations '", changedClass.getName());
        context.affectUsage(new ClassNewUsage(changedClass.getReferenceID()), n -> isKotlinNode(n));
      }
    }

    return true;
  }

  @Override
  public boolean processChangedMethod(DifferentiateContext context, Difference.Change<JvmClass, JvmClass.Diff> clsChange, Difference.Change<JvmMethod, JvmMethod.Diff> methodChange, Utils future, Utils present) {
    JvmClass changedClass = clsChange.getPast();
    JvmMethod changedMethod = methodChange.getPast();

    if (methodChange.getDiff().valueChanged()) {
      String name = getMethodKotlinName(changedClass, changedMethod);
      debug("Function was inlineable, or has become inlineable or a body of inline method has changed; affecting method usages ", name);
      affectMemberLookupUsages(context, changedClass, name, future);
    }
    return true;
  }

  @Override
  public boolean processRemovedMethod(DifferentiateContext context, Difference.Change<JvmClass, JvmClass.Diff> change, JvmMethod removedMethod, Utils future, Utils present) {
    JvmClass changedClass = change.getPast();
    KmFunction kmFunction = getKmFunction(changedClass, removedMethod);
    if (kmFunction != null && Attributes.isInline(kmFunction)) {
      debug("Function was inlineable, affecting method usages ", kmFunction.getName());
      affectMemberLookupUsages(context, changedClass, kmFunction.getName(), present);
    }
    return true;
  }

  @Override
  public boolean processRemovedField(DifferentiateContext context, Difference.Change<JvmClass, JvmClass.Diff> change, JvmField removedField, Utils future, Utils present) {
    if (!removedField.isPrivate() && removedField.isInlinable() && removedField.getValue() != null) {
      debug("Field had value and was (non-private) final; affecting usages in Kotlin sources ");
      JvmClass changedClass = change.getPast();
      affectLookupUsages(
        context,
        flat(asIterable(changedClass.getReferenceID()), present.collectSubclassesWithoutField(changedClass.getReferenceID(), removedField)),
        removedField.getName(),
        present,
        null
      );
    }

    return true;
  }

  @Override
  public boolean processChangedField(DifferentiateContext context, Difference.Change<JvmClass, JvmClass.Diff> clsChange, Difference.Change<JvmField, JvmField.Diff> fieldChange, Utils future, Utils present) {
    JvmClass changedClass = clsChange.getPast();
    JvmField changedField = fieldChange.getPast();
    if (!changedField.isPrivate() && changedField.isInlinable() && changedField.getValue() != null) { // if the field was a compile-time constant
      JvmField.Diff diff = fieldChange.getDiff();
      if (diff.valueChanged() || diff.accessRestricted() || find(List.of(diff.getAddedFlags(), diff.getRemovedFlags()), f -> f.isStatic() || f.isFinal()) != null) {
        debug("Potentially inlined field changed its access or value; affecting usages in Kotlin sources ");
        affectLookupUsages(
          context,
          flat(asIterable(changedClass.getReferenceID()), present.collectSubclassesWithoutField(changedClass.getReferenceID(), changedField)),
          changedField.getName(),
          present,
          null
        );
      }
    }

    return true;
  }

  private void affectConflictingCallExpressions(DifferentiateContext context, JvmClass cls, JvmMethod clsMethod, Utils utils, @Nullable Predicate<Node<?, ?>> constraint) {
    if (clsMethod.isPrivate()) {
      return;
    }
    if (clsMethod.isConstructor()) {
      affectClassLookupUsages(context, cls);
    }
    else {
      Set<JvmNodeReferenceID> targets = collect(
        flat(utils.allSupertypes(cls.getReferenceID()), utils.collectSubclassesWithoutMethod(cls.getReferenceID(), clsMethod)), new SmartHashSet<>()
      );
      targets.add(cls.getReferenceID());
      affectLookupUsages(context, targets, getMethodKotlinName(cls, clsMethod), utils, constraint);
    }
  }

  private static final class PropertyDescriptor{
    @NotNull
    final String name;
    @NotNull
    final JvmMethod getter;
    @Nullable
    final JvmMethod setter;

    PropertyDescriptor(@NotNull String name, @NotNull JvmMethod getter, @Nullable JvmMethod setter) {
      this.name = name;
      this.getter = getter;
      this.setter = setter;
    }
  }

  private static Iterable<PropertyDescriptor> findProperties(JvmClass cls) {
    Map<String, JvmMethod> getters = new HashMap<>();
    Map<String, List<JvmMethod>> setters = new HashMap<>();
    for (JvmMethod method : cls.getMethods()) {
      String methodName = method.getName();
      if (isGetter(method)) {
        getters.put(methodName.substring(methodName.startsWith("is")? 2 : 3), method);
      }
      else if (isSetter(method)) {
        setters.computeIfAbsent(methodName.substring(3), k -> new SmartList<>()).add(method);
      }
    }
    return map(getters.entrySet(), e -> {
      String propName = e.getKey();
      JvmMethod getter = e.getValue();
      for (JvmMethod setter : filter(setters.get(propName), s -> Objects.equals(s.getArgTypes().iterator().next(), getter.getType()))) {
        return new PropertyDescriptor(propName, getter, setter);
      }
      return new PropertyDescriptor(propName, getter, null);
    });
  }

  private static boolean isSetter(JvmMethod method) {
    String name = method.getName();
    return name.length() > 3 && name.startsWith("set") && "V".equals(method.getType().getDescriptor()) && sizeEqual(method.getArgTypes(), 1);
  }

  private static boolean isGetter(JvmMethod method) {
    if (!isEmpty(method.getArgTypes())) {
      return false;
    }
    String name = method.getName();
    if (name.length() > 3 && name.startsWith("get")) {
      return true;
    }
    if (name.length() > 2 && name.startsWith("is")) {
      TypeRepr returnType = method.getType();
      return TypeRepr.PrimitiveType.BOOLEAN.equals(returnType) || TypeRepr.ClassType.BOOLEAN.equals(returnType);
    }
    return false;
  }

  private static boolean sizeEqual(Iterable<?> it, int expectedSize) {
    if (it instanceof Collection) {
      return expectedSize == ((Collection<?>)it).size();
    }
    Iterator<?> iterator = it.iterator();
    while (expectedSize-- > 0) {
      if (!iterator.hasNext()) {
        return false;
      }
      iterator.next();
    }
    return !iterator.hasNext();
  }

  private void affectClassLookupUsages(DifferentiateContext context, JvmClass cls) {
    String scope;
    String name;
    String ktName = getKotlinName(cls);
    if (ktName != null) {
      scope = JvmClass.getPackageName(ktName);
      name = JvmClass.getShortName(ktName);
    }
    else { // not a kotlin-compiled class or a synthetic kotlin class
      if (cls.isInnerClass()) {
        String fqName = cls.getName();
        String outerFqName = cls.getOuterFqName();
        scope = outerFqName.replace('$', '/');
        name = fqName.length() > outerFqName.length() && fqName.startsWith(outerFqName)? cls.getName().substring(outerFqName.length() + 1 /* separator char, usually '$' */) : cls.getShortName();
      }
      else {
        scope = cls.getPackageName();
        name = cls.getShortName();
      }
    }
    affectUsages(context, "lookup '" + name + "'" , asIterable(new JvmNodeReferenceID(scope)), id -> new LookupNameUsage(id, name), null);
  }

  private void affectMemberLookupUsages(DifferentiateContext context, JvmClass cls, String name, Utils utils) {
    affectLookupUsages(context, filter(map(utils.withAllSubclasses(cls.getReferenceID()), id -> id instanceof JvmNodeReferenceID? ((JvmNodeReferenceID)id) : null), Objects::nonNull), name, utils, null);
  }

  private void affectLookupUsages(DifferentiateContext context, Iterable<JvmNodeReferenceID> symbolOwners, String symbolName, Utils utils, @Nullable Predicate<Node<?, ?>> constraint) {
    // since '$' is both a valid bytecode name symbol and inner class name separator, for every class name containing '$' use additional classname with '/'
    Iterable<JvmNodeReferenceID> owners = filter(flat(symbolOwners, map(symbolOwners, o -> {
      String original = o.getNodeName();
      String normalized = original.replace('$', '/'); // inner class names on Kotlin lookups level use '/' separators instead of '$'
      return normalized.equals(original)? null : new JvmNodeReferenceID(normalized);
    })), Objects::nonNull);

    affectUsages(context, "lookup '" + symbolName + "'" , owners, id -> {
      String kotlinName = getKotlinName(id, utils);
      return new LookupNameUsage(kotlinName != null ? new JvmNodeReferenceID(kotlinName) : id, symbolName);
    }, constraint);
  }

  private static KmFunction getKmFunction(JvmClass cls, JvmMethod method) {
    JvmMethodSignature methodSignature = new JvmMethodSignature(method.getName(), method.getDescriptor());
    return find(allKmFunctions(cls), f -> methodSignature.equals(JvmExtensionsKt.getSignature(f)));
  }

  private static @Nullable JvmMethod getJvmMethod(JvmClass cls, JvmMethodSignature sig) {
    return sig != null? find(cls.getMethods(), m -> Objects.equals(m.getName(), sig.getName()) && Objects.equals(m.getDescriptor(), sig.getDescriptor())) : null;
  }
  private static Iterable<JvmMethod> withJvmOverloads(JvmClass cls, JvmMethod method) {
    return unique(flat(
      asIterable(method),
      filter(cls.getMethods(), m -> Objects.equals(m.getName(), method.getName()) && Objects.equals(m.getType(), method.getType()) && find(m.getAnnotations(), a -> JVM_OVERLOADS_ANNOTATION.equals(a.getAnnotationClass())) != null)
    ));
  }

  private static Iterable<KmFunction> allKmFunctions(Node<?, ?> node) {
    KotlinMeta meta = getKotlinMeta(node);
    return meta != null? meta.getKmFunctions() : Collections.emptyList();
  }

  private static Iterable<KmProperty> allKmProperties(Node<?, ?> node) {
    KotlinMeta meta = getKotlinMeta(node);
    return meta != null? meta.getKmProperties() : Collections.emptyList();
  }

  @Nullable
  private static String getKotlinName(JvmNodeReferenceID cls, Utils utils) {
    return find(map(utils.getNodes(cls, JvmClass.class), c -> getKotlinName(c)), Objects::nonNull);
  }

  @Nullable
  private static String getKotlinName(JvmClass cls) {
    KmDeclarationContainer container = getDeclarationContainer(cls);
    if (container instanceof KmPackage) {
      return cls.getPackageName();
    }
    if (container instanceof KmClass) {
      return ((KmClass)container).getName().replace('.', '/');
    }
    return null;
  }

  private static String getMethodKotlinName(JvmClass cls, JvmMethod method) {
    JvmMethodSignature sig = new JvmMethodSignature(method.getName(), method.getDescriptor());
    for (KmFunction f : allKmFunctions(cls)) {
      if (sig.equals(JvmExtensionsKt.getSignature(f))) {
        return f.getName();
      }
    }
    for (KmProperty p : allKmProperties(cls)) {
      JvmMethodSignature getterSig = JvmExtensionsKt.getGetterSignature(p);
      if (sig.equals(getterSig)) {
        return getterSig.getName();
      }
      if (p.getSetter() != null) {
        JvmMethodSignature setterSig = JvmExtensionsKt.getSetterSignature(p);
        if (sig.equals(setterSig)) {
          return setterSig.getName();
        }
      }
    }
    return method.getName();
  }

  private static boolean isDeclaresDefaultValue(KmFunction f) {
    return find(f.getValueParameters(), Attributes::getDeclaresDefaultValue) != null;
  }

  private static KmDeclarationContainer getDeclarationContainer(Node<?, ?> node) {
    KotlinMeta meta = getKotlinMeta(node);
    return meta != null? meta.getDeclarationContainer() : null;
  }

  private static boolean isKotlinNode(Node<?, ?> node) {
    return getKotlinMeta(node) != null;
  }

  private static @Nullable KotlinMeta getKotlinMeta(Node<?, ?> node) {
    return node instanceof JVMClassNode? (KotlinMeta)find(((JVMClassNode<?, ?>)node).getMetadata(), mt -> mt instanceof KotlinMeta) : null;
  }

}
