package io.kurlew.pipeline

/**
 * Represents the immutable subject that flows through the DataPipeline.
 *
 * This is the raw input data acquired at the beginning of the pipeline.
 * It serves as the reliable, unaltered source of truth throughout processing.
 *
 * Mutable state is stored in DataPipelineCall (the context), keeping the
 * subject immutable and thread-safe.
 */
data class DataEvent<T>(
    /**
     * The immutable raw input acquired at the beginning of the pipeline.
     * This serves as the reliable, unaltered source of truth.
     */
    val incoming: T
)
