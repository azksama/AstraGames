package fr.astragames.app.core.search

import fr.astragames.app.core.model.TagMatchMode

object TagMatcher {
    fun matches(selected: Set<String>, gameTags: Set<String>, mode: TagMatchMode): Boolean = when (mode) {
        TagMatchMode.ALL -> selected.all(gameTags::contains)
        TagMatchMode.ANY -> selected.isEmpty() || selected.any(gameTags::contains)
        TagMatchMode.EXCLUDE -> selected.none(gameTags::contains)
    }
}
