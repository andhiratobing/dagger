/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.auto.common.MoreTypes.isType;
import static com.google.auto.common.MoreTypes.isTypeOf;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.any;
import static com.google.common.collect.Iterables.isEmpty;
import static dagger.internal.codegen.ComponentDescriptor.isComponentContributionMethod;
import static dagger.internal.codegen.ComponentRequirement.Kind.BOUND_INSTANCE;
import static dagger.internal.codegen.DaggerStreams.toImmutableSet;
import static dagger.internal.codegen.RequestKinds.getRequestKind;
import static dagger.internal.codegen.Util.reentrantComputeIfAbsent;
import static dagger.model.BindingKind.OPTIONAL;
import static dagger.model.BindingKind.SUBCOMPONENT_BUILDER;
import static java.util.function.Predicate.isEqual;
import static javax.lang.model.util.ElementFilter.methodsIn;

import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import dagger.MembersInjector;
import dagger.Reusable;
import dagger.internal.codegen.ComponentDescriptor.BuilderRequirementMethod;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodKind;
import dagger.model.DependencyRequest;
import dagger.model.Key;
import dagger.model.Scope;
import dagger.producers.Produced;
import dagger.producers.Producer;
import dagger.releasablereferences.CanReleaseReferences;
import dagger.releasablereferences.ReleasableReferenceManager;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/** A factory for {@link BindingGraph} objects. */
final class BindingGraphFactory {
  private final DaggerElements elements;
  private final InjectBindingRegistry injectBindingRegistry;
  private final KeyFactory keyFactory;
  private final BindingFactory bindingFactory;

  @Inject
  BindingGraphFactory(
      DaggerElements elements,
      InjectBindingRegistry injectBindingRegistry,
      KeyFactory keyFactory,
      BindingFactory bindingFactory) {
    this.elements = elements;
    this.injectBindingRegistry = injectBindingRegistry;
    this.keyFactory = keyFactory;
    this.bindingFactory = bindingFactory;
  }

  /** Creates a binding graph for a root component. */
  BindingGraph create(ComponentDescriptor componentDescriptor) {
    checkArgument(componentDescriptor.kind().isTopLevel());
    return create(Optional.empty(), componentDescriptor);
  }

  private BindingGraph create(
      Optional<Resolver> parentResolver, ComponentDescriptor componentDescriptor) {
    ImmutableSet.Builder<ContributionBinding> explicitBindingsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<DelegateDeclaration> delegatesBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<OptionalBindingDeclaration> optionalsBuilder = ImmutableSet.builder();

    // binding for the component itself
    explicitBindingsBuilder.add(
        bindingFactory.componentBinding(componentDescriptor.componentDefinitionType()));

    // Collect Component dependencies.
    for (ComponentRequirement dependency : componentDescriptor.dependencies()) {
      explicitBindingsBuilder.add(bindingFactory.componentDependencyBinding(dependency));
      List<ExecutableElement> dependencyMethods =
          methodsIn(elements.getAllMembers(dependency.typeElement()));
      for (ExecutableElement method : dependencyMethods) {
        // MembersInjection methods aren't "provided" explicitly, so ignore them.
        if (isComponentContributionMethod(elements, method)) {
          explicitBindingsBuilder.add(
              bindingFactory.componentDependencyMethodBinding(componentDescriptor, method));
        }
      }
    }

    // Collect bindings on the builder.
    if (componentDescriptor.builderSpec().isPresent()) {
      for (BuilderRequirementMethod method :
          componentDescriptor.builderSpec().get().requirementMethods()) {
        if (method.requirement().kind().equals(BOUND_INSTANCE)) {
          explicitBindingsBuilder.add(bindingFactory.boundInstanceBinding(method));
        }
      }
    }

    for (Map.Entry<ComponentMethodDescriptor, ComponentDescriptor> componentMethodAndSubcomponent :
        componentDescriptor.subcomponentsByBuilderMethod().entrySet()) {
      ComponentMethodDescriptor componentMethod = componentMethodAndSubcomponent.getKey();
      ComponentDescriptor subcomponentDescriptor = componentMethodAndSubcomponent.getValue();
      if (!componentDescriptor.subcomponentsFromModules().contains(subcomponentDescriptor)) {
        explicitBindingsBuilder.add(
            bindingFactory.subcomponentBuilderBinding(
                componentMethod.methodElement(), componentDescriptor.componentDefinitionType()));
      }
    }

    ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations = ImmutableSet.builder();
    ImmutableSet.Builder<SubcomponentDeclaration> subcomponentDeclarations = ImmutableSet.builder();

    // Collect transitive module bindings and multibinding declarations.
    for (ModuleDescriptor moduleDescriptor : componentDescriptor.transitiveModules()) {
      explicitBindingsBuilder.addAll(moduleDescriptor.bindings());
      multibindingDeclarations.addAll(moduleDescriptor.multibindingDeclarations());
      subcomponentDeclarations.addAll(moduleDescriptor.subcomponentDeclarations());
      delegatesBuilder.addAll(moduleDescriptor.delegateDeclarations());
      optionalsBuilder.addAll(moduleDescriptor.optionalDeclarations());
    }

    ImmutableSetMultimap<Scope, ProvisionBinding> releasableReferenceManagerBindings =
        getReleasableReferenceManagerBindings(componentDescriptor);
    explicitBindingsBuilder.addAll(releasableReferenceManagerBindings.values());

    final Resolver requestResolver =
        new Resolver(
            parentResolver,
            componentDescriptor,
            indexBindingDeclarationsByKey(explicitBindingsBuilder.build()),
            indexBindingDeclarationsByKey(multibindingDeclarations.build()),
            indexBindingDeclarationsByKey(subcomponentDeclarations.build()),
            indexBindingDeclarationsByKey(delegatesBuilder.build()),
            indexBindingDeclarationsByKey(optionalsBuilder.build()));
    for (ComponentMethodDescriptor componentMethod : componentDescriptor.componentMethods()) {
      if (componentMethod.kind().equals(ComponentMethodKind.MEMBERS_INJECTION)) {
        requestResolver.resolveMembersInjectionMethod(componentMethod);
      } else {
        Optional<DependencyRequest> componentMethodRequest = componentMethod.dependencyRequest();
        if (componentMethodRequest.isPresent()) {
          requestResolver.resolve(componentMethodRequest.get().key());
        }
      }
    }

    // Resolve all bindings for subcomponents, creating subgraphs for all subcomponents that have
    // been detected during binding resolution. If a binding for a subcomponent is never resolved,
    // no BindingGraph will be created for it and no implementation will be generated. This is
    // done in a queue since resolving one subcomponent might resolve a key for a subcomponent
    // from a parent graph. This is done until no more new subcomponents are resolved.
    Set<ComponentDescriptor> resolvedSubcomponents = new HashSet<>();
    ImmutableSet.Builder<BindingGraph> subgraphs = ImmutableSet.builder();
    for (ComponentDescriptor subcomponent :
        Iterables.consumingIterable(requestResolver.subcomponentsToResolve)) {
      if (resolvedSubcomponents.add(subcomponent)) {
        subgraphs.add(create(Optional.of(requestResolver), subcomponent));
      }
    }

    ImmutableMap<Key, ResolvedBindings> resolvedContributionBindingsMap =
        requestResolver.getResolvedContributionBindings();
    for (ResolvedBindings resolvedBindings : resolvedContributionBindingsMap.values()) {
      verify(
          resolvedBindings.owningComponent().equals(componentDescriptor),
          "%s is not owned by %s",
          resolvedBindings,
          componentDescriptor);
    }

    return BindingGraph.create(
        componentDescriptor,
        resolvedContributionBindingsMap,
        requestResolver.getResolvedMembersInjectionBindings(),
        subgraphs.build(),
        getScopesRequiringReleasableReferenceManagers(
            releasableReferenceManagerBindings, resolvedContributionBindingsMap.keySet()),
        requestResolver.getOwnedModules());
  }

  /**
   * Returns the bindings for {@link ReleasableReferenceManager}s for all {@link
   * CanReleaseReferences @CanReleaseReferences} scopes.
   */
  private ImmutableSetMultimap<Scope, ProvisionBinding> getReleasableReferenceManagerBindings(
      ComponentDescriptor componentDescriptor) {
    ImmutableSetMultimap.Builder<Scope, ProvisionBinding> bindings = ImmutableSetMultimap.builder();
    // TODO(dpb,gak): Do we need to bind an empty Set<ReleasableReferenceManager> if there are
    // none?
    for (Scope scope : componentDescriptor.releasableReferencesScopes()) {
      // Add a binding for @ForReleasableReferences(scope) ReleasableReferenceManager.
      bindings.put(scope, bindingFactory.releasableReferenceManagerBinding(scope));

      /* Add a binding for Set<ReleasableReferenceManager>. Even if these are added more than
       * once, each instance will be equal to the rest. Since they're being added to a set, there
       * will be only one instance. */
      bindings.put(scope, bindingFactory.setOfReleasableReferenceManagersBinding());

      for (AnnotationMirror metadata : scope.releasableReferencesMetadata()) {
        // Add a binding for @ForReleasableReferences(scope) TypedReleasableReferenceManager<M>.
        bindings.put(
            scope,
            bindingFactory.typedReleasableReferenceManagerBinding(
                scope, metadata.getAnnotationType()));

        /* Add a binding for Set<TypedReleasableReferenceManager<M>>. Even if these are added more
         * than once, each instance will be equal to the rest. Since they're being added to a set,
         * there will be only one instance. */
        bindings.put(
            scope,
            bindingFactory.setOfTypedReleasableReferenceManagersBinding(
                metadata.getAnnotationType()));
      }
    }
    return bindings.build();
  }

  /**
   * Returns the set of scopes that will be returned by {@link
   * BindingGraph#scopesRequiringReleasableReferenceManagers()}.
   *
   * @param releasableReferenceManagerBindings the {@link ReleasableReferenceManager} bindings for
   *     each scope
   * @param resolvedContributionKeys the keys of the resolved bindings for the component
   */
  private ImmutableSet<Scope> getScopesRequiringReleasableReferenceManagers(
      ImmutableSetMultimap<Scope, ProvisionBinding> releasableReferenceManagerBindings,
      ImmutableSet<Key> resolvedContributionKeys) {
    ImmutableSet.Builder<Scope> scopes = ImmutableSet.builder();
    releasableReferenceManagerBindings
        .asMap()
        .forEach(
            (scope, bindings) -> {
              for (Binding binding : bindings) {
                if (resolvedContributionKeys.contains(binding.key())) {
                  scopes.add(scope);
                  return;
                }
              }
            });
    return scopes.build();
  }

  /** Indexes {@code bindingDeclarations} by {@link BindingDeclaration#key()}. */
  private static <T extends BindingDeclaration>
      ImmutableSetMultimap<Key, T> indexBindingDeclarationsByKey(Iterable<T> declarations) {
    return ImmutableSetMultimap.copyOf(Multimaps.index(declarations, BindingDeclaration::key));
  }

  private final class Resolver {
    final Optional<Resolver> parentResolver;
    final ComponentDescriptor componentDescriptor;
    final ImmutableSetMultimap<Key, ContributionBinding> explicitBindings;
    final ImmutableSet<ContributionBinding> explicitBindingsSet;
    final ImmutableSetMultimap<Key, ContributionBinding> explicitMultibindings;
    final ImmutableSetMultimap<Key, MultibindingDeclaration> multibindingDeclarations;
    final ImmutableSetMultimap<Key, SubcomponentDeclaration> subcomponentDeclarations;
    final ImmutableSetMultimap<Key, DelegateDeclaration> delegateDeclarations;
    final ImmutableSetMultimap<Key, OptionalBindingDeclaration> optionalBindingDeclarations;
    final ImmutableSetMultimap<Key, DelegateDeclaration> delegateMultibindingDeclarations;
    final Map<Key, ResolvedBindings> resolvedContributionBindings = new LinkedHashMap<>();
    final Map<Key, ResolvedBindings> resolvedMembersInjectionBindings = new LinkedHashMap<>();
    final Deque<Key> cycleStack = new ArrayDeque<>();
    final Map<Key, Boolean> keyDependsOnLocalBindingsCache = new HashMap<>();
    final Map<Binding, Boolean> bindingDependsOnLocalBindingsCache = new HashMap<>();
    final Queue<ComponentDescriptor> subcomponentsToResolve = new ArrayDeque<>();

    Resolver(
        Optional<Resolver> parentResolver,
        ComponentDescriptor componentDescriptor,
        ImmutableSetMultimap<Key, ContributionBinding> explicitBindings,
        ImmutableSetMultimap<Key, MultibindingDeclaration> multibindingDeclarations,
        ImmutableSetMultimap<Key, SubcomponentDeclaration> subcomponentDeclarations,
        ImmutableSetMultimap<Key, DelegateDeclaration> delegateDeclarations,
        ImmutableSetMultimap<Key, OptionalBindingDeclaration> optionalBindingDeclarations) {
      this.parentResolver = checkNotNull(parentResolver);
      this.componentDescriptor = checkNotNull(componentDescriptor);
      this.explicitBindings = checkNotNull(explicitBindings);
      this.explicitBindingsSet = ImmutableSet.copyOf(explicitBindings.values());
      this.multibindingDeclarations = checkNotNull(multibindingDeclarations);
      this.subcomponentDeclarations = checkNotNull(subcomponentDeclarations);
      this.delegateDeclarations = checkNotNull(delegateDeclarations);
      this.optionalBindingDeclarations = checkNotNull(optionalBindingDeclarations);
      this.explicitMultibindings = multibindingContributionsByMultibindingKey(explicitBindingsSet);
      this.delegateMultibindingDeclarations =
          multibindingContributionsByMultibindingKey(delegateDeclarations.values());
      subcomponentsToResolve.addAll(componentDescriptor.subcomponentsFromEntryPoints());
    }

    /**
     * Returns the resolved contribution bindings for the given {@link Key}:
     *
     * <ul>
     *   <li>All explicit bindings for:
     *       <ul>
     *         <li>the requested key
     *         <li>{@code Set<T>} if the requested key's type is {@code Set<Produced<T>>}
     *         <li>{@code Map<K, Provider<V>>} if the requested key's type is {@code Map<K,
     *             Producer<V>>}.
     *       </ul>
     *   <li>A synthetic binding that depends on {@code Map<K, Producer<V>>} if the requested key's
     *       type is {@code Map<K, V>} and there are some explicit bindings for {@code Map<K,
     *       Producer<V>>}.
     *   <li>A synthetic binding that depends on {@code Map<K, Provider<V>>} if the requested key's
     *       type is {@code Map<K, V>} and there are some explicit bindings for {@code Map<K,
     *       Provider<V>>} but no explicit bindings for {@code Map<K, Producer<V>>}.
     *   <li>An implicit {@link Inject @Inject}-annotated constructor binding if there is one and
     *       there are no explicit bindings or synthetic bindings.
     * </ul>
     */
    ResolvedBindings lookUpBindings(Key requestKey) {
      Set<ContributionBinding> bindings = new LinkedHashSet<>();
      bindings.addAll(getExplicitBindings(requestKey));

      ImmutableSet<ContributionBinding> multibindingContributions =
          getAllMatchingBindingDeclarations(requestKey, this::getExplicitMultibindings);
      ImmutableSet<MultibindingDeclaration> multibindingDeclarations =
          getAllMatchingBindingDeclarations(requestKey, this::getMultibindingDeclarations);

      syntheticMultibinding(requestKey, multibindingContributions, multibindingDeclarations)
          .ifPresent(bindings::add);

      ImmutableSet<OptionalBindingDeclaration> optionalBindingDeclarations =
          getAllMatchingBindingDeclarations(requestKey, this::getOptionalBindingDeclarations);
      syntheticOptionalBinding(requestKey, optionalBindingDeclarations).ifPresent(bindings::add);

      ImmutableSet<SubcomponentDeclaration> subcomponentDeclarations =
          getSubcomponentDeclarations(requestKey);
      syntheticSubcomponentBuilderBinding(subcomponentDeclarations)
          .ifPresent(
              binding -> {
                bindings.add(binding);
                addSubcomponentToOwningResolver(binding);
              });

      if (isType(requestKey.type()) && isTypeOf(MembersInjector.class, requestKey.type())) {
        injectBindingRegistry
            .getOrFindMembersInjectorProvisionBinding(requestKey)
            .ifPresent(bindings::add);
      }

      // If there are no bindings, add the implicit @Inject-constructed binding if there is one.
      if (bindings.isEmpty()) {
        injectBindingRegistry.getOrFindProvisionBinding(requestKey).ifPresent(bindings::add);
      }

      return ResolvedBindings.forContributionBindings(
          requestKey,
          componentDescriptor,
          indexBindingsByOwningComponent(requestKey, ImmutableSet.copyOf(bindings)),
          multibindingDeclarations,
          subcomponentDeclarations,
          optionalBindingDeclarations);
    }

    /** Returns the resolved members injection bindings for the given {@link Key}. */
    ResolvedBindings lookUpMembersInjectionBinding(Key requestKey) {
      // no explicit deps for members injection, so just look it up
      Optional<MembersInjectionBinding> binding =
          injectBindingRegistry.getOrFindMembersInjectionBinding(requestKey);
      return binding.isPresent()
          ? ResolvedBindings.forMembersInjectionBinding(
              requestKey, componentDescriptor, binding.get())
          : ResolvedBindings.noBindings(requestKey, componentDescriptor);
    }

    /**
     * When a binding is resolved for a {@link SubcomponentDeclaration}, adds corresponding {@link
     * ComponentDescriptor subcomponent} to a queue in the owning component's resolver. The queue
     * will be used to detect which subcomponents need to be resolved.
     */
    private void addSubcomponentToOwningResolver(ProvisionBinding subcomponentBuilderBinding) {
      checkArgument(subcomponentBuilderBinding.kind().equals(SUBCOMPONENT_BUILDER));
      Resolver owningResolver = getOwningResolver(subcomponentBuilderBinding).get();

      TypeElement builderType = MoreTypes.asTypeElement(subcomponentBuilderBinding.key().type());
      owningResolver.subcomponentsToResolve.add(
          owningResolver.componentDescriptor.subcomponentsByBuilderType().get(builderType));
    }

    private ImmutableSet<Key> keysMatchingRequest(Key requestKey) {
      ImmutableSet.Builder<Key> keys = ImmutableSet.builder();
      keys.add(requestKey);
      keyFactory.unwrapSetKey(requestKey, Produced.class).ifPresent(keys::add);
      keyFactory.rewrapMapKey(requestKey, Producer.class, Provider.class).ifPresent(keys::add);
      keyFactory.rewrapMapKey(requestKey, Provider.class, Producer.class).ifPresent(keys::add);
      keys.addAll(keyFactory.implicitFrameworkMapKeys(requestKey));
      return keys.build();
    }

    /**
     * Returns a synthetic binding that depends on individual multibinding contributions.
     *
     * <p>If there are no {@code multibindingContributions} or {@code multibindingDeclarations},
     * returns {@link Optional#empty()}.
     *
     * <p>If there are production {@code multibindingContributions} or the request is for any of the
     * following types, returns a {@link ProductionBinding}.
     *
     * <ul>
     *   <li>{@code Set<Produced<T>>}
     *   <li>{@code Map<K, Producer<V>>}
     *   <li>{@code Map<K, Produced<V>>}
     * </ul>
     *
     * Otherwise, returns a {@link ProvisionBinding}.
     */
    private Optional<ContributionBinding> syntheticMultibinding(
        Key key,
        Iterable<ContributionBinding> multibindingContributions,
        Iterable<MultibindingDeclaration> multibindingDeclarations) {
      return isEmpty(multibindingContributions) && isEmpty(multibindingDeclarations)
          ? Optional.empty()
          : Optional.of(bindingFactory.syntheticMultibinding(key, multibindingContributions));
    }

    private Optional<ProvisionBinding> syntheticSubcomponentBuilderBinding(
        ImmutableSet<SubcomponentDeclaration> subcomponentDeclarations) {
      return subcomponentDeclarations.isEmpty()
          ? Optional.empty()
          : Optional.of(bindingFactory.subcomponentBuilderBinding(subcomponentDeclarations));
    }

    /**
     * Returns a synthetic binding for {@code @Qualifier Optional<Type>} if there are any {@code
     * optionalBindingDeclarations}.
     *
     * <p>If there are no bindings for the underlying key (the key for dependency requests for
     * {@code Type}), returns a provision binding that always returns {@link Optional#empty()}.
     *
     * <p>If there are any production bindings for the underlying key, returns a production binding.
     * Otherwise returns a provision binding.
     */
    private Optional<ContributionBinding> syntheticOptionalBinding(
        Key key, ImmutableSet<OptionalBindingDeclaration> optionalBindingDeclarations) {
      return optionalBindingDeclarations.isEmpty()
          ? Optional.empty()
          : Optional.of(
              bindingFactory.syntheticOptionalBinding(
                  key,
                  getRequestKind(OptionalType.from(key).valueType()),
                  lookUpBindings(keyFactory.unwrapOptional(key).get())));
    }

    private ImmutableSet<ContributionBinding> createDelegateBindings(
        ImmutableSet<DelegateDeclaration> delegateDeclarations) {
      ImmutableSet.Builder<ContributionBinding> builder = ImmutableSet.builder();
      for (DelegateDeclaration delegateDeclaration : delegateDeclarations) {
        builder.add(createDelegateBinding(delegateDeclaration));
      }
      return builder.build();
    }

    /**
     * Creates one (and only one) delegate binding for a delegate declaration, based on the resolved
     * bindings of the right-hand-side of a {@link dagger.Binds} method. If there are duplicate
     * bindings for the dependency key, there should still be only one binding for the delegate key.
     */
    private ContributionBinding createDelegateBinding(DelegateDeclaration delegateDeclaration) {
      Key delegateKey = delegateDeclaration.delegateRequest().key();
      if (cycleStack.contains(delegateKey)) {
        return bindingFactory.missingDelegateBinding(delegateDeclaration);
      }

      ResolvedBindings resolvedDelegate;
      try {
        cycleStack.push(delegateKey);
        resolvedDelegate = lookUpBindings(delegateKey);
      } finally {
        cycleStack.pop();
      }
      if (resolvedDelegate.contributionBindings().isEmpty()) {
        // This is guaranteed to result in a missing binding error, so it doesn't matter if the
        // binding is a Provision or Production, except if it is a @IntoMap method, in which
        // case the key will be of type Map<K, Provider<V>>, which will be "upgraded" into a
        // Map<K, Producer<V>> if it's requested in a ProductionComponent. This may result in a
        // strange error, that the RHS needs to be provided with an @Inject or @Provides
        // annotated method, but a user should be able to figure out if a @Produces annotation
        // is needed.
        // TODO(gak): revisit how we model missing delegates if/when we clean up how we model
        // binding declarations
        return bindingFactory.missingDelegateBinding(delegateDeclaration);
      }
      // It doesn't matter which of these is selected, since they will later on produce a
      // duplicate binding error.
      ContributionBinding explicitDelegate =
          resolvedDelegate.contributionBindings().iterator().next();
      return bindingFactory.delegateBinding(delegateDeclaration, explicitDelegate);
    }

    // TODO(dpb,ronshapiro): requestKey appears to be interchangeable with each binding's .key(),
    // but should it? We're currently conflating the two all over the place and it would be good
    // to unify, or if it's necessary, clarify why with docs+tests. Specifically, should we also
    // be checking these for keysMatchingRequest?
    private ImmutableSetMultimap<ComponentDescriptor, ContributionBinding>
        indexBindingsByOwningComponent(
            Key requestKey, Iterable<? extends ContributionBinding> bindings) {
      ImmutableSetMultimap.Builder<ComponentDescriptor, ContributionBinding> index =
          ImmutableSetMultimap.builder();
      for (ContributionBinding binding : bindings) {
        index.put(getOwningComponent(requestKey, binding), binding);
      }
      return index.build();
    }

    /**
     * Returns the component that should contain the framework field for {@code binding}.
     *
     * <p>If {@code binding} is either not bound in an ancestor component or depends transitively on
     * bindings in this component, returns this component.
     *
     * <p>Otherwise, resolves {@code request} in this component's parent in order to resolve any
     * multibinding contributions in the parent, and returns the parent-resolved {@link
     * ResolvedBindings#owningComponent(ContributionBinding)}.
     */
    private ComponentDescriptor getOwningComponent(Key requestKey, ContributionBinding binding) {
      if (isResolvedInParent(requestKey, binding)
          && !new LocalDependencyChecker().dependsOnLocalBindings(binding)) {
        ResolvedBindings parentResolvedBindings =
            parentResolver.get().resolvedContributionBindings.get(requestKey);
        return parentResolvedBindings.owningComponent(binding);
      } else {
        return componentDescriptor;
      }
    }

    /**
     * Returns {@code true} if {@code binding} is owned by an ancestor. If so, {@linkplain #resolve
     * resolves} the {@link Key} in this component's parent. Don't resolve directly in the owning
     * component in case it depends on multibindings in any of its descendants.
     */
    private boolean isResolvedInParent(Key requestKey, ContributionBinding binding) {
      Optional<Resolver> owningResolver = getOwningResolver(binding);
      if (owningResolver.isPresent() && !owningResolver.get().equals(this)) {
        parentResolver.get().resolve(requestKey);
        return true;
      } else {
        return false;
      }
    }

    private Optional<Resolver> getOwningResolver(ContributionBinding binding) {
      if (binding.scope().isPresent() && binding.scope().get().isReusable()) {
        for (Resolver requestResolver : getResolverLineage().reverse()) {
          // If a @Reusable binding was resolved in an ancestor, use that component.
          if (requestResolver.resolvedContributionBindings.containsKey(binding.key())) {
            return Optional.of(requestResolver);
          }
        }
        // If a @Reusable binding was not resolved in any ancestor, resolve it here.
        return Optional.empty();
      }

      for (Resolver requestResolver : getResolverLineage().reverse()) {
        if (requestResolver.explicitBindingsSet.contains(binding)
            || requestResolver.subcomponentDeclarations.containsKey(binding.key())) {
          return Optional.of(requestResolver);
        }
      }

      // look for scope separately.  we do this for the case where @Singleton can appear twice
      // in the † compatibility mode
      Optional<Scope> bindingScope = binding.scope();
      if (bindingScope.isPresent()) {
        for (Resolver requestResolver : getResolverLineage().reverse()) {
          if (requestResolver.componentDescriptor.scopes().contains(bindingScope.get())) {
            return Optional.of(requestResolver);
          }
        }
      }
      return Optional.empty();
    }

    /** Returns the resolver lineage from parent to child. */
    private ImmutableList<Resolver> getResolverLineage() {
      List<Resolver> resolverList = Lists.newArrayList();
      for (Optional<Resolver> currentResolver = Optional.of(this);
          currentResolver.isPresent();
          currentResolver = currentResolver.get().parentResolver) {
        resolverList.add(currentResolver.get());
      }
      return ImmutableList.copyOf(Lists.reverse(resolverList));
    }

    /**
     * For all {@linkplain #keysMatchingRequest(Key) keys matching {@code requestKey}}, applies
     * {@code getDeclarationsPerKey} and collects the values into an {@link ImmutableSet}.
     */
    private <T extends BindingDeclaration> ImmutableSet<T> getAllMatchingBindingDeclarations(
        Key requestKey, Function<Key, Collection<T>> getDeclarationsPerKey) {
      return keysMatchingRequest(requestKey)
          .stream()
          .flatMap(key -> getDeclarationsPerKey.apply(key).stream())
          .collect(toImmutableSet());
    }

    /**
     * Returns the explicit {@link ContributionBinding}s that match the {@code key} from this and
     * all ancestor resolvers.
     */
    private ImmutableSet<ContributionBinding> getExplicitBindings(Key key) {
      ImmutableSet.Builder<ContributionBinding> bindings = ImmutableSet.builder();
      for (Resolver resolver : getResolverLineage()) {
        bindings.addAll(resolver.getLocalExplicitBindings(key));
      }
      return bindings.build();
    }

    /**
     * Returns the explicit {@link ContributionBinding}s that match the {@code key} from this
     * resolver.
     */
    private ImmutableSet<ContributionBinding> getLocalExplicitBindings(Key key) {
      return new ImmutableSet.Builder<ContributionBinding>()
          .addAll(explicitBindings.get(key))
          .addAll(
              createDelegateBindings(
                  delegateDeclarations.get(keyFactory.convertToDelegateKey(key))))
          .build();
    }

    /**
     * Returns the explicit multibinding contributions that contribute to the map or set requested
     * by {@code key} from this and all ancestor resolvers.
     */
    private ImmutableSet<ContributionBinding> getExplicitMultibindings(Key key) {
      ImmutableSet.Builder<ContributionBinding> multibindings = ImmutableSet.builder();
      for (Resolver resolver : getResolverLineage()) {
        multibindings.addAll(resolver.getLocalExplicitMultibindings(key));
      }
      return multibindings.build();
    }

    /**
     * Returns the explicit multibinding contributions that contribute to the map or set requested
     * by {@code key} from this resolver.
     */
    private ImmutableSet<ContributionBinding> getLocalExplicitMultibindings(Key key) {
      ImmutableSet.Builder<ContributionBinding> multibindings = ImmutableSet.builder();
      multibindings.addAll(explicitMultibindings.get(key));
      if (!MapType.isMap(key)
          || MapType.from(key).isRawType()
          || MapType.from(key).valuesAreFrameworkType()) {
        // There are no @Binds @IntoMap delegate declarations for Map<K, V> requests. All
        // @IntoMap requests must be for Map<K, Framework<V>>.
        multibindings.addAll(
            createDelegateBindings(
                delegateMultibindingDeclarations.get(keyFactory.convertToDelegateKey(key))));
      }
      return multibindings.build();
    }

    /**
     * Returns the {@link MultibindingDeclaration}s that match the {@code key} from this and all
     * ancestor resolvers.
     */
    private ImmutableSet<MultibindingDeclaration> getMultibindingDeclarations(Key key) {
      ImmutableSet.Builder<MultibindingDeclaration> multibindingDeclarations =
          ImmutableSet.builder();
      for (Resolver resolver : getResolverLineage()) {
        multibindingDeclarations.addAll(resolver.multibindingDeclarations.get(key));
      }
      return multibindingDeclarations.build();
    }

    /**
     * Returns the {@link SubcomponentDeclaration}s that match the {@code key} from this and all
     * ancestor resolvers.
     */
    private ImmutableSet<SubcomponentDeclaration> getSubcomponentDeclarations(Key key) {
      ImmutableSet.Builder<SubcomponentDeclaration> subcomponentDeclarations =
          ImmutableSet.builder();
      for (Resolver resolver : getResolverLineage()) {
        subcomponentDeclarations.addAll(resolver.subcomponentDeclarations.get(key));
      }
      return subcomponentDeclarations.build();
    }
    /**
     * Returns the {@link OptionalBindingDeclaration}s that match the {@code key} from this and all
     * ancestor resolvers.
     */
    private ImmutableSet<OptionalBindingDeclaration> getOptionalBindingDeclarations(Key key) {
      Optional<Key> unwrapped = keyFactory.unwrapOptional(key);
      if (!unwrapped.isPresent()) {
        return ImmutableSet.of();
      }
      ImmutableSet.Builder<OptionalBindingDeclaration> declarations = ImmutableSet.builder();
      for (Resolver resolver : getResolverLineage()) {
        declarations.addAll(resolver.optionalBindingDeclarations.get(unwrapped.get()));
      }
      return declarations.build();
    }

    /**
     * Returns the {@link ResolvedBindings} for {@code key} that was resolved in this resolver or an
     * ancestor resolver. Only checks for {@link ContributionBinding}s as {@link
     * MembersInjectionBinding}s are not inherited.
     */
    private Optional<ResolvedBindings> getPreviouslyResolvedBindings(Key key) {
      Optional<ResolvedBindings> result =
          Optional.ofNullable(resolvedContributionBindings.get(key));
      if (result.isPresent()) {
        return result;
      } else if (parentResolver.isPresent()) {
        return parentResolver.get().getPreviouslyResolvedBindings(key);
      } else {
        return Optional.empty();
      }
    }

    private void resolveMembersInjectionMethod(ComponentMethodDescriptor componentMethod) {
      checkArgument(componentMethod.kind().equals(ComponentMethodKind.MEMBERS_INJECTION));
      Key key = componentMethod.dependencyRequest().get().key();
      ResolvedBindings bindings = lookUpMembersInjectionBinding(key);
      resolveDependencies(bindings);
      resolvedMembersInjectionBindings.put(key, bindings);
    }

    void resolve(Key key) {
      // If we find a cycle, stop resolving. The original request will add it with all of the
      // other resolved deps.
      if (cycleStack.contains(key)) {
        return;
      }

      // If the binding was previously resolved in this (sub)component, don't resolve it again.
      if (resolvedContributionBindings.containsKey(key)) {
        return;
      }

      /* If the binding was previously resolved in a supercomponent, then we may be able to avoid
       * resolving it here and just depend on the supercomponent resolution.
       *
       * 1. If it depends transitively on multibinding contributions or optional bindings with
       *    bindings from this subcomponent, then we have to resolve it in this subcomponent so
       *    that it sees the local bindings.
       *
       * 2. If there are any explicit bindings in this component, they may conflict with those in
       *    the supercomponent, so resolve them here so that conflicts can be caught.
       */
      if (getPreviouslyResolvedBindings(key).isPresent()) {
        /* Resolve in the parent in case there are multibinding contributions or conflicts in some
         * component between this one and the previously-resolved one. */
        parentResolver.get().resolve(key);
        if (!new LocalDependencyChecker().dependsOnLocalBindings(key)
            && getLocalExplicitBindings(key).isEmpty()) {
          /* Cache the inherited parent component's bindings in case resolving at the parent found
           * bindings in some component between this one and the previously-resolved one. */
          ResolvedBindings inheritedBindings =
              getPreviouslyResolvedBindings(key).get().asInheritedIn(componentDescriptor);
          resolvedContributionBindings.put(key, inheritedBindings);
          return;
        }
      }

      cycleStack.push(key);
      try {
        ResolvedBindings bindings = lookUpBindings(key);
        resolveDependencies(bindings);
        resolvedContributionBindings.put(key, bindings);
      } finally {
        cycleStack.pop();
      }
    }

    /**
     * {@link #resolve(Key) Resolves} each of the dependencies of the {@link
     * ResolvedBindings#ownedBindings() owned bindings} of {@code resolvedBindings}.
     */
    private void resolveDependencies(ResolvedBindings resolvedBindings) {
      for (Binding binding : resolvedBindings.ownedBindings()) {
        for (DependencyRequest dependency : binding.dependencies()) {
          resolve(dependency.key());
        }
      }
    }

    /**
     * Returns all of the {@link ResolvedBindings} for {@link ContributionBinding}s from this and
     * all ancestor resolvers, indexed by {@link ResolvedBindings#key()}.
     */
    ImmutableMap<Key, ResolvedBindings> getResolvedContributionBindings() {
      ImmutableMap.Builder<Key, ResolvedBindings> builder = ImmutableMap.builder();
      builder.putAll(resolvedContributionBindings);
      if (parentResolver.isPresent()) {
        ImmutableMap<Key, ResolvedBindings> parentBindings =
            parentResolver.get().getResolvedContributionBindings();
        Collection<ResolvedBindings> bindingsResolvedInParent =
            Maps.difference(parentBindings, resolvedContributionBindings)
                .entriesOnlyOnLeft()
                .values();
        for (ResolvedBindings resolvedInParent : bindingsResolvedInParent) {
          builder.put(resolvedInParent.key(), resolvedInParent.asInheritedIn(componentDescriptor));
        }
      }
      return builder.build();
    }

    /**
     * Returns all of the {@link ResolvedBindings} for {@link MembersInjectionBinding} from this
     * resolvers, indexed by {@link ResolvedBindings#key()}.
     */
    ImmutableMap<Key, ResolvedBindings> getResolvedMembersInjectionBindings() {
      return ImmutableMap.copyOf(resolvedMembersInjectionBindings);
    }

    ImmutableSet<ModuleDescriptor> getInheritedModules() {
      return parentResolver.isPresent()
          ? Sets.union(
                  parentResolver.get().getInheritedModules(),
                  parentResolver.get().componentDescriptor.transitiveModules())
              .immutableCopy()
          : ImmutableSet.<ModuleDescriptor>of();
    }

    ImmutableSet<ModuleDescriptor> getOwnedModules() {
      return Sets.difference(componentDescriptor.transitiveModules(), getInheritedModules())
          .immutableCopy();
    }

    private final class LocalDependencyChecker {
      private final Set<Object> cycleChecker = new HashSet<>();

      /**
       * Returns {@code true} if any of the bindings resolved for {@code key} are multibindings with
       * contributions declared within this component's modules or optional bindings with present
       * values declared within this component's modules, or if any of its unscoped dependencies
       * depend on such bindings.
       *
       * <p>We don't care about scoped dependencies because they will never depend on bindings from
       * subcomponents.
       *
       * @throws IllegalArgumentException if {@link #getPreviouslyResolvedBindings(Key)} is empty
       */
      boolean dependsOnLocalBindings(Key key) {
        // Don't recur infinitely if there are valid cycles in the dependency graph.
        // http://b/23032377
        if (!cycleChecker.add(key)) {
          return false;
        }
        return reentrantComputeIfAbsent(
            keyDependsOnLocalBindingsCache, key, this::dependsOnLocalBindingsUncached);
      }

      private boolean dependsOnLocalBindingsUncached(Key key) {
        checkArgument(
            getPreviouslyResolvedBindings(key).isPresent(),
            "no previously resolved bindings in %s for %s",
            Resolver.this,
            key);
        ResolvedBindings previouslyResolvedBindings = getPreviouslyResolvedBindings(key).get();
        if (hasLocalMultibindingContributions(previouslyResolvedBindings)
            || hasLocallyPresentOptionalBinding(previouslyResolvedBindings)) {
          return true;
        }

        for (Binding binding : previouslyResolvedBindings.bindings()) {
          if (dependsOnLocalBindings(binding)) {
            return true;
          }
        }
        return false;
      }

      /**
       * Returns {@code true} if {@code binding} is unscoped (or has {@link Reusable @Reusable}
       * scope) and depends on multibindings with contributions declared within this component's
       * modules, or if any of its unscoped or {@link Reusable @Reusable} scoped dependencies depend
       * on such local multibindings.
       *
       * <p>We don't care about non-reusable scoped dependencies because they will never depend on
       * multibindings with contributions from subcomponents.
       */
      boolean dependsOnLocalBindings(Binding binding) {
        if (!cycleChecker.add(binding)) {
          return false;
        }
        return reentrantComputeIfAbsent(
            bindingDependsOnLocalBindingsCache, binding, this::dependsOnLocalBindingsUncached);
      }

      private boolean dependsOnLocalBindingsUncached(Binding binding) {
        if ((!binding.scope().isPresent() || binding.scope().get().isReusable())
            // TODO(beder): Figure out what happens with production subcomponents.
            && !binding.bindingType().equals(BindingType.PRODUCTION)) {
          for (DependencyRequest dependency : binding.dependencies()) {
            if (dependsOnLocalBindings(dependency.key())) {
              return true;
            }
          }
        }
        return false;
      }

      /**
       * Returns {@code true} if {@code resolvedBindings} contains a synthetic multibinding with at
       * least one contribution declared within this component's modules.
       */
      private boolean hasLocalMultibindingContributions(ResolvedBindings resolvedBindings) {
        return any(
                resolvedBindings.contributionBindings(),
                ContributionBinding::isSyntheticMultibinding)
            && keysMatchingRequest(resolvedBindings.key())
                .stream()
                .anyMatch(key -> !getLocalExplicitMultibindings(key).isEmpty());
      }

      /**
       * Returns {@code true} if {@code resolvedBindings} contains a synthetic optional binding for
       * which there is an explicit present binding in this component.
       */
      private boolean hasLocallyPresentOptionalBinding(ResolvedBindings resolvedBindings) {
        return resolvedBindings
                .contributionBindings()
                .stream()
                .map(ContributionBinding::kind)
                .anyMatch(isEqual(OPTIONAL))
            && !getLocalExplicitBindings(keyFactory.unwrapOptional(resolvedBindings.key()).get())
                .isEmpty();
      }
    }
  }

  /**
   * A multimap of those {@code declarations} that are multibinding contribution declarations,
   * indexed by the key of the set or map to which they contribute.
   */
  static <T extends BindingDeclaration>
      ImmutableSetMultimap<Key, T> multibindingContributionsByMultibindingKey(
          Iterable<T> declarations) {
    ImmutableSetMultimap.Builder<Key, T> builder = ImmutableSetMultimap.builder();
    for (T declaration : declarations) {
      if (declaration.key().multibindingContributionIdentifier().isPresent()) {
        builder.put(
            declaration
                .key()
                .toBuilder()
                .multibindingContributionIdentifier(Optional.empty())
                .build(),
            declaration);
      }
    }
    return builder.build();
  }
}