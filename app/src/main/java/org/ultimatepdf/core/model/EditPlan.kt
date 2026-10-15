package org.ultimatepdf.core.model

import android.net.Uri

/**
 * Non-destructive editing architecture inspired by AnonPDF.
 * Operations are recorded in an immutable list, enabling full Undo/Redo,
 * preview simulation, and batch export without altering the source file until commit.
 */
sealed interface EditOp {
    fun describe(): String
}

data class CropInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    val isEmpty: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

data class RotatePages(val degrees: Int, val targets: Set<Int>) : EditOp {
    override fun describe() = "Rotate ${degrees}° on ${formatTargets(targets)}"
}

data class CropPages(val insets: CropInsets, val targets: Set<Int>) : EditOp {
    override fun describe() = "Crop on ${formatTargets(targets)}"
}

data class DeletePages(val targets: Set<Int>) : EditOp {
    override fun describe() = "Delete ${formatTargets(targets)}"
}

data class RestorePages(val targets: Set<Int>) : EditOp {
    override fun describe() = "Restore ${formatTargets(targets)}"
}

data class MovePage(val sourcePosition: Int, val targetPosition: Int) : EditOp {
    override fun describe() = "Move page ${sourcePosition + 1} to ${targetPosition + 1}"
}

data class InsertPages(
    val sourceUri: Uri,
    val targetPosition: Int,
    val pageCount: Int,
    val label: String
) : EditOp {
    override fun describe() = "Insert $pageCount page(s) from \"$label\" at position ${targetPosition + 1}"
}

/** Inserts a copy of each target page right after it. */
data class DuplicatePages(val targets: Set<Int>) : EditOp {
    override fun describe() = "Duplicate ${formatTargets(targets)}"
}

data object ReversePages : EditOp {
    override fun describe() = "Reverse page order"
}

data class WatermarkOptions(
    val text: String,
    val angleDegrees: Float = 45f,
    val opacity: Float = 0.3f,
    val fontSizePt: Float = 48f,
    val colorHex: Long = 0x888888
)

data class SetWatermark(val options: WatermarkOptions?) : EditOp {
    override fun describe() = options?.let { "Watermark \"${it.text}\"" } ?: "Remove watermark"
}

data class PageNumberOptions(
    val format: String = "Page {page} of {total}",
    val bottomMarginPt: Float = 36f,
    val fontSizePt: Float = 10f
)

data class SetPageNumbers(val options: PageNumberOptions?) : EditOp {
    override fun describe() = options?.let { "Page numbers: ${it.format}" } ?: "Remove page numbers"
}

private fun formatTargets(targets: Set<Int>): String =
    if (targets.isEmpty()) "no pages"
    else if (targets.size == 1) "page ${targets.first() + 1}"
    else "${targets.size} pages (${targets.map { it + 1 }.sorted().joinToString(", ")})"

/**
 * Normalised state of an individual page within the EditPlan.
 */
data class PageState(
    val sourceIndex: Int,
    val rotationDelta: Int = 0,
    val crop: CropInsets = CropInsets(),
    val deleted: Boolean = false,
    val sourceDocumentUri: Uri? = null // null means the primary open document
) {
    val isUntouched: Boolean
        get() = rotationDelta == 0 && crop.isEmpty && !deleted && sourceDocumentUri == null
}

/**
 * Normalised document edit plan. Drives both the UI page-grid preview and final PDFBox export.
 */
data class EditPlan(
    val pages: List<PageState>,
    val watermark: WatermarkOptions? = null,
    val pageNumbers: PageNumberOptions? = null,
    val basePageCount: Int = pages.size
) {
    val activePages: List<PageState> get() = pages.filterNot { it.deleted }

    val hasChanges: Boolean
        get() = watermark != null ||
                pageNumbers != null ||
                pages.size != basePageCount ||
                pages.withIndex().any { (index, state) ->
                    state.sourceIndex != index || !state.isUntouched
                }

    /** Applies [transform] to the pages at the given positions in [pages]. */
    fun mapTargets(targets: Set<Int>, transform: (PageState) -> PageState): EditPlan {
        return copy(pages = pages.mapIndexed { idx, state ->
            if (targets.contains(idx)) {
                transform(state)
            } else {
                state
            }
        })
    }
}

/**
 * Folds a sequential list of [EditOp]s into a unified [EditPlan].
 */
object EditPlanBuilder {

    fun identity(pageCount: Int): EditPlan =
        EditPlan((0 until pageCount).map { PageState(sourceIndex = it) })

    fun build(pageCount: Int, ops: List<EditOp>): EditPlan =
        ops.fold(identity(pageCount)) { plan, op -> apply(plan, op) }

    private fun apply(plan: EditPlan, op: EditOp): EditPlan = when (op) {
        is RotatePages -> plan.mapTargets(op.targets) {
            it.copy(rotationDelta = (it.rotationDelta + op.degrees) % 360)
        }

        is CropPages -> plan.mapTargets(op.targets) {
            it.copy(crop = op.insets)
        }

        is DeletePages -> plan.mapTargets(op.targets) {
            it.copy(deleted = true)
        }

        is RestorePages -> plan.mapTargets(op.targets) {
            it.copy(deleted = false)
        }

        is MovePage -> {
            val list = plan.pages.toMutableList()
            if (op.sourcePosition in list.indices && op.targetPosition in list.indices) {
                val item = list.removeAt(op.sourcePosition)
                list.add(op.targetPosition, item)
            }
            plan.copy(pages = list)
        }

        is InsertPages -> {
            val list = plan.pages.toMutableList()
            val insertedPages = (0 until op.pageCount).map { subIndex ->
                PageState(
                    sourceIndex = subIndex,
                    sourceDocumentUri = op.sourceUri
                )
            }
            val insertAt = op.targetPosition.coerceIn(0, list.size)
            list.addAll(insertAt, insertedPages)
            plan.copy(pages = list)
        }

        is ReversePages -> {
            plan.copy(pages = plan.pages.reversed())
        }

        is DuplicatePages -> {
            plan.copy(pages = plan.pages.flatMapIndexed { index, page ->
                if (index in op.targets) listOf(page, page) else listOf(page)
            })
        }

        is SetWatermark -> {
            plan.copy(watermark = op.options)
        }

        is SetPageNumbers -> {
            plan.copy(pageNumbers = op.options)
        }
    }
}
