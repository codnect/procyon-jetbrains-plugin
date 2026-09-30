package io.codnect.procyon

import com.goide.GoFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper

internal object GoFiles {

    /**
     * Returns the project's Go files that contain [word]. The word index is tried first; when it
     * finds nothing the Go files are read directly, so the result never depends on it alone.
     */
    fun withWord(
        project: Project,
        word: String,
        scope: GlobalSearchScope = GlobalSearchScope.projectScope(project),
        mustContain: String? = null,
    ): List<PsiFile> {

        val indexed = mutableListOf<PsiFile>()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(
            word,
            scope,
            { file -> if (file.name.endsWith(".go") && hasText(file, mustContain)) indexed += file; true },
            true,
        )
        if (indexed.isNotEmpty() || scope != GlobalSearchScope.projectScope(project)) return indexed

        val psiManager = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(GoFileType.INSTANCE, scope)
            .filter { VfsUtilCore.loadText(it).let { text -> text.contains(word) && (mustContain == null || text.contains(mustContain)) } }
            .mapNotNull { psiManager.findFile(it) }
    }

    private fun hasText(file: PsiFile, text: String?): Boolean =
        text == null || file.viewProvider.virtualFile.let { VfsUtilCore.loadText(it).contains(text) }
}
