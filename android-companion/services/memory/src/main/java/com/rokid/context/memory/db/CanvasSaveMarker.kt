package com.rokid.context.memory.db

/**
 * v0.9.6 (Playground vs Run-and-Save): the `canvas_meta` key that says a canvas run's memories were saved (value: how many). It holds a run id and a count, no content. It is what makes
 * a save idempotent (a retry or a double click never writes twice, not even after the user forgot one of the saved memories) and it goes with the run (`CanvasDao.deleteRun`) and with Delete all.
 */
object CanvasSaveMarker {
    const val PREFIX = "canvas_save:"
    fun key(runId: String) = PREFIX + runId
}
