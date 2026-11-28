package org.rsmod.api.type.editors.comp

import jakarta.inject.Inject
import org.rsmod.api.type.editors.TypeEditor
import org.rsmod.api.type.editors.resolver.TypeEditorResolver
import org.rsmod.api.type.editors.resolver.TypeEditorResult
import org.rsmod.api.type.editors.resolver.TypeEditorResult.CachePackRequired
import org.rsmod.api.type.editors.resolver.TypeEditorResult.CacheTypeDoesNotExit
import org.rsmod.api.type.editors.resolver.TypeEditorResult.FullSuccess
import org.rsmod.api.type.editors.resolver.TypeEditorResult.NameNotFound
import org.rsmod.api.type.editors.resolver.err
import org.rsmod.api.type.editors.resolver.ok
import org.rsmod.api.type.editors.resolver.update
import org.rsmod.api.type.symbols.name.NameMapping
import org.rsmod.game.type.comp.ComponentTypeList

public class ComponentEditorResolver
@Inject
constructor(private val types: ComponentTypeList, private val nameMapping: NameMapping) :
    TypeEditorResolver<ComponentOpEdit> {
    override fun resolve(editors: TypeEditor<ComponentOpEdit>): List<TypeEditorResult> =
        editors.cache.map { it.resolve() }

    private fun ComponentOpEdit.resolve(): TypeEditorResult {
        val packed = nameMapping.components[name] ?: return err(NameNotFound(name))
        val resolved = copy(packed = packed)
        val cacheType = types[packed] ?: return resolved.err(CacheTypeDoesNotExit)
        val edited = ComponentEditor.applyOps(cacheType.op, ops)
        if (!edited.contentEquals(cacheType.op)) {
            return resolved.update(CachePackRequired)
        }
        return resolved.ok(FullSuccess)
    }
}
