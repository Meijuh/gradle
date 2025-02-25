/*
 * Copyright 2007-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Category;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.configurations.Configurations;
import org.gradle.api.internal.artifacts.configurations.ConfigurationsProvider;
import org.gradle.api.internal.artifacts.dependencies.SelfResolvingDependencyInternal;
import org.gradle.api.internal.attributes.AttributeValue;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.internal.DisplayName;
import org.gradle.internal.component.external.model.ImmutableCapabilities;
import org.gradle.internal.component.local.model.DefaultLocalConfigurationMetadata;
import org.gradle.internal.component.local.model.LocalComponentMetadata;
import org.gradle.internal.component.local.model.LocalConfigurationMetadata;
import org.gradle.internal.component.local.model.LocalFileDependencyMetadata;
import org.gradle.internal.component.local.model.LocalVariantMetadata;
import org.gradle.internal.component.model.ComponentConfigurationIdentifier;
import org.gradle.internal.component.model.ExcludeMetadata;
import org.gradle.internal.component.model.LocalOriginDependencyMetadata;
import org.gradle.internal.component.model.VariantResolveMetadata;
import org.gradle.internal.model.CalculatedValueContainerFactory;
import org.gradle.internal.model.ModelContainer;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Encapsulates all logic required to build a {@link LocalConfigurationMetadata} from a
 * {@link ConfigurationInternal}. Utilizes caching to prevent unnecessary duplicate conversions
 * between DSL and internal metadata types.
 */
public class DefaultLocalConfigurationMetadataBuilder implements LocalConfigurationMetadataBuilder {
    private final DependencyDescriptorFactory dependencyDescriptorFactory;
    private final ExcludeRuleConverter excludeRuleConverter;

    public DefaultLocalConfigurationMetadataBuilder(DependencyDescriptorFactory dependencyDescriptorFactory,
                                                    ExcludeRuleConverter excludeRuleConverter) {
        this.dependencyDescriptorFactory = dependencyDescriptorFactory;
        this.excludeRuleConverter = excludeRuleConverter;
    }

    @Override
    public LocalConfigurationMetadata create(
        ConfigurationInternal configuration,
        ConfigurationsProvider configurationsProvider,
        LocalComponentMetadata parent,
        DependencyCache dependencyCache,
        ModelContainer<?> model,
        CalculatedValueContainerFactory calculatedValueContainerFactory
    ) {
        ComponentIdentifier componentId = parent.getId();
        ComponentConfigurationIdentifier configurationIdentifier = new ComponentConfigurationIdentifier(componentId, configuration.getName());

        // Collect all artifacts and sub-variants.
        ImmutableList.Builder<PublishArtifact> artifactBuilder = ImmutableList.builder();
        ImmutableSet.Builder<LocalVariantMetadata> variantsBuilder = ImmutableSet.builder();
        configuration.collectVariants(new ConfigurationInternal.VariantVisitor() {
            @Override
            public void visitArtifacts(Collection<? extends PublishArtifact> artifacts) {
                artifactBuilder.addAll(artifacts);
            }

            @Override
            public void visitOwnVariant(DisplayName displayName, ImmutableAttributes attributes, Collection<? extends Capability> capabilities, Collection<? extends PublishArtifact> artifacts) {
                variantsBuilder.add(new LocalVariantMetadata(configuration.getName(), configurationIdentifier, componentId, displayName, attributes, artifacts, ImmutableCapabilities.of(capabilities), model, calculatedValueContainerFactory));
            }

            @Override
            public void visitChildVariant(String name, DisplayName displayName, ImmutableAttributes attributes, Collection<? extends Capability> capabilities, Collection<? extends PublishArtifact> artifacts) {
                variantsBuilder.add(new LocalVariantMetadata(configuration.getName() + "-" + name, new NestedVariantIdentifier(configurationIdentifier, name), componentId, displayName, attributes, artifacts, ImmutableCapabilities.of(capabilities), model, calculatedValueContainerFactory));
            }
        });

        // Collect all dependencies and excludes in hierarchy.
        ImmutableAttributes attributes = configuration.getAttributes().asImmutable();
        ImmutableSet<String> hierarchy = Configurations.getNames(configuration.getHierarchy());
        DependencyState dependencies = getState(configurationsProvider, hierarchy, componentId, dependencyCache);

        return new DefaultLocalConfigurationMetadata(
            configuration.getName(),
            configuration.getDescription(),
            componentId,
            configuration.isVisible(),
            configuration.isTransitive(),
            hierarchy,
            attributes,
            ImmutableCapabilities.of(Configurations.collectCapabilities(configuration, Sets.newHashSet(), Sets.newHashSet())),
            configuration.isCanBeConsumed(),
            configuration.getConsumptionDeprecation(),
            configuration.isCanBeResolved(),
            maybeForceDependencies(dependencies.dependencies, attributes),
            dependencies.files,
            dependencies.excludes,
            variantsBuilder.build(),
            artifactBuilder.build(),
            model,
            calculatedValueContainerFactory,
            parent
        );
    }

    /**
     * Collect all dependencies and excludes of all configurations in the provided {@code hierarchy}.
     */
    public DependencyState getState(
        ConfigurationsProvider configurations,
        ImmutableSet<String> hierarchy,
        ComponentIdentifier componentId,
        DependencyCache cache
    ) {
        ImmutableList.Builder<LocalOriginDependencyMetadata> dependencies = ImmutableList.builder();
        ImmutableSet.Builder<LocalFileDependencyMetadata> files = ImmutableSet.builder();
        ImmutableList.Builder<ExcludeMetadata> excludes = ImmutableList.builder();

        for (ConfigurationInternal config : configurations.getAll()) {
            if (hierarchy.contains(config.getName())) {
                DependencyState defined = getDefinedState(config, componentId, cache);
                dependencies.addAll(defined.dependencies);
                files.addAll(defined.files);
                excludes.addAll(defined.excludes);
            }
        }

        return new DependencyState(dependencies.build(), files.build(), excludes.build());
    }

    /**
     * Get the defined dependencies and excludes for {@code configuration}, while also caching the result.
     */
    private DependencyState getDefinedState(ConfigurationInternal configuration, ComponentIdentifier componentId, DependencyCache cache) {
        return cache.computeIfAbsent(configuration, componentId, this::doGetDefinedState);
    }

    /**
     * Calculate the defined dependencies and excludes for {@code configuration}, while converting the
     * DSL representation to the internal representation.
     */
    private DependencyState doGetDefinedState(ConfigurationInternal configuration, ComponentIdentifier componentId) {
        // Run any actions to add/modify dependencies
        configuration.runDependencyActions();

        AttributeContainer attributes = configuration.getAttributes();

        ImmutableList.Builder<LocalOriginDependencyMetadata> dependencyBuilder = ImmutableList.builder();
        ImmutableSet.Builder<LocalFileDependencyMetadata> fileBuilder = ImmutableSet.builder();
        ImmutableList.Builder<ExcludeMetadata> excludeBuilder = ImmutableList.builder();

        for (Dependency dependency : configuration.getDependencies()) {
            if (dependency instanceof ModuleDependency) {
                ModuleDependency moduleDependency = (ModuleDependency) dependency;
                dependencyBuilder.add(dependencyDescriptorFactory.createDependencyDescriptor(
                    componentId, configuration.getName(), attributes, moduleDependency
                ));
            } else if (dependency instanceof FileCollectionDependency) {
                final FileCollectionDependency fileDependency = (FileCollectionDependency) dependency;
                fileBuilder.add(new DefaultLocalFileDependencyMetadata(fileDependency));
            } else {
                throw new IllegalArgumentException("Cannot convert dependency " + dependency + " to local component dependency metadata.");
            }
        }

        for (DependencyConstraint dependencyConstraint : configuration.getDependencyConstraints()) {
            dependencyBuilder.add(dependencyDescriptorFactory.createDependencyConstraintDescriptor(
                componentId, configuration.getName(), attributes, dependencyConstraint)
            );
        }

        for (ExcludeRule excludeRule : configuration.getExcludeRules()) {
            excludeBuilder.add(excludeRuleConverter.convertExcludeRule(excludeRule));
        }

        return new DependencyState(dependencyBuilder.build(), fileBuilder.build(), excludeBuilder.build());
    }

    private static ImmutableList<LocalOriginDependencyMetadata> maybeForceDependencies(
        ImmutableList<LocalOriginDependencyMetadata> dependencies,
        ImmutableAttributes attributes
    ) {
        AttributeValue<Category> attributeValue = attributes.findEntry(Category.CATEGORY_ATTRIBUTE);
        if (!attributeValue.isPresent() || !attributeValue.get().getName().equals(Category.ENFORCED_PLATFORM)) {
            return dependencies;
        }

        // Need to wrap all dependencies to force them.
        ImmutableList.Builder<LocalOriginDependencyMetadata> forcedDependencies = ImmutableList.builder();
        for (LocalOriginDependencyMetadata rawDependency : dependencies) {
            forcedDependencies.add(rawDependency.forced());
        }
        return forcedDependencies.build();
    }

    /**
     * {@link VariantResolveMetadata.Identifier} implementation for non-implicit sub-variants of a configuration.
     */
    private static class NestedVariantIdentifier implements VariantResolveMetadata.Identifier {
        private final VariantResolveMetadata.Identifier parent;
        private final String name;

        public NestedVariantIdentifier(VariantResolveMetadata.Identifier parent, String name) {
            this.parent = parent;
            this.name = name;
        }

        @Override
        public int hashCode() {
            return parent.hashCode() ^ name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            NestedVariantIdentifier other = (NestedVariantIdentifier) obj;
            return parent.equals(other.parent) && name.equals(other.name);
        }
    }

    /**
     * Default implementation of {@link LocalFileDependencyMetadata}.
     */
    private static class DefaultLocalFileDependencyMetadata implements LocalFileDependencyMetadata {
        private final FileCollectionDependency fileDependency;

        DefaultLocalFileDependencyMetadata(FileCollectionDependency fileDependency) {
            this.fileDependency = fileDependency;
        }

        @Override
        public FileCollectionDependency getSource() {
            return fileDependency;
        }

        @Override @Nullable
        public ComponentIdentifier getComponentId() {
            return ((SelfResolvingDependencyInternal) fileDependency).getTargetComponentId();
        }

        @Override
        public FileCollectionInternal getFiles() {
            return (FileCollectionInternal) fileDependency.getFiles();
        }
    }
}
