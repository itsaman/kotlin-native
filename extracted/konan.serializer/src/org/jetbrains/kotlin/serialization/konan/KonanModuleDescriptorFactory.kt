package org.jetbrains.kotlin.serialization.konan

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.konan.DeserializedKonanModuleOrigin
import org.jetbrains.kotlin.descriptors.konan.createKonanModuleDescriptor
import org.jetbrains.kotlin.descriptors.konan.interop.InteropFqNames
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.konan.library.KonanLibrary
import org.jetbrains.kotlin.konan.library.exportForwardDeclarations
import org.jetbrains.kotlin.konan.library.isInterop
import org.jetbrains.kotlin.konan.library.packageFqName
import org.jetbrains.kotlin.konan.library.resolver.PackageAccessedHandler
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.storage.StorageManager

// FIXME(ddol): this is a temporary solution, to be refactored into some global resolution context
interface KonanModuleDescriptorFactory {

    fun createModuleDescriptor(
            library: KonanLibrary,
            specifics: LanguageVersionSettings,
            packageAccessedHandler: PackageAccessedHandler? = null,
            storageManager: StorageManager = LockBasedStorageManager()
    ): ModuleDescriptor
}

object DefaultKonanModuleDescriptorFactory: KonanModuleDescriptorFactory {

    override fun createModuleDescriptor(
            library: KonanLibrary,
            specifics: LanguageVersionSettings,
            packageAccessedHandler: PackageAccessedHandler?,
            storageManager: StorageManager
    ): ModuleDescriptorImpl {

        val libraryProto = parseModuleHeader(library.moduleHeaderData)

        val moduleName = libraryProto.moduleName

        val moduleDescriptor = createKonanModuleDescriptor(
                Name.special(moduleName),
                storageManager,
                origin = DeserializedKonanModuleOrigin(library)
        )
        val deserializationConfiguration = CompilerDeserializationConfiguration(specifics)

        val provider = createPackageFragmentProvider(
                library,
                packageAccessedHandler,
                libraryProto.packageFragmentNameList,
                storageManager,
                moduleDescriptor,
                deserializationConfiguration)

        moduleDescriptor.initialize(provider)

        return moduleDescriptor
    }

    private fun createPackageFragmentProvider(
            library: KonanLibrary,
            packageAccessedHandler: PackageAccessedHandler?,
            fragmentNames: List<String>,
            storageManager: StorageManager,
            moduleDescriptor: ModuleDescriptor,
            configuration: DeserializationConfiguration
    ): PackageFragmentProvider {

        val deserializedPackageFragments = fragmentNames.map{
            KonanPackageFragment(FqName(it), library, packageAccessedHandler, storageManager, moduleDescriptor)
        }

        val syntheticPackageFragments = getSyntheticPackageFragments(
                library,
                moduleDescriptor,
                deserializedPackageFragments)

        val provider = PackageFragmentProviderImpl(deserializedPackageFragments + syntheticPackageFragments)

        val notFoundClasses = NotFoundClasses(storageManager, moduleDescriptor)

        val annotationAndConstantLoader = AnnotationAndConstantLoaderImpl(
                moduleDescriptor,
                notFoundClasses,
                KonanSerializerProtocol)

        val components = DeserializationComponents(
                storageManager,
                moduleDescriptor,
                configuration,
                DeserializedClassDataFinder(provider),
                annotationAndConstantLoader,
                provider,
                LocalClassifierTypeSettings.Default,
                ErrorReporter.DO_NOTHING,
                LookupTracker.DO_NOTHING,
                NullFlexibleTypeDeserializer,
                emptyList(),
                notFoundClasses,
                ContractDeserializer.DEFAULT,
                extensionRegistryLite = KonanSerializerProtocol.extensionRegistry)

        for (packageFragment in deserializedPackageFragments) {
            packageFragment.initialize(components)
        }

        return provider
    }

    private fun getSyntheticPackageFragments(
            library: KonanLibrary,
            moduleDescriptor: ModuleDescriptor,
            konanPackageFragments: List<KonanPackageFragment>
    ): List<PackageFragmentDescriptor> {

        if (!library.isInterop) return emptyList()

        val packageFqName = library.packageFqName
                ?: error("Inconsistent manifest: interop library ${library.libraryName} should have `package` specified")

        val exportForwardDeclarations = library.exportForwardDeclarations

        val interopPackageFragments = konanPackageFragments.filter { it.fqName == packageFqName }

        val result = mutableListOf<PackageFragmentDescriptor>()

        // Allow references to forwarding declarations to be resolved into classifiers declared in this library:
        listOf(InteropFqNames.cNamesStructs, InteropFqNames.objCNamesClasses, InteropFqNames.objCNamesProtocols).mapTo(result) { fqName ->
            ClassifierAliasingPackageFragmentDescriptor(interopPackageFragments, moduleDescriptor, fqName)
        }
        // TODO: use separate namespaces for structs, enums, Objective-C protocols etc.

        result.add(ExportedForwardDeclarationsPackageFragmentDescriptor(moduleDescriptor, packageFqName, exportForwardDeclarations))

        return result
    }
}
