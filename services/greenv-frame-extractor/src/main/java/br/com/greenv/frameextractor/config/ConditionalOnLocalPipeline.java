package br.com.greenv.frameextractor.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Marks the legacy whole-video pipeline, which resolves every artifact to a path under
 * {@code greenv.extractor.root} and therefore exists only while the local object-storage adapter
 * is selected. A cloud deployment runs the segment path alone, so these beans must disappear with
 * the local store they depend on instead of failing the context at startup.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "greenv.adapters.object-storage", havingValue = "local", matchIfMissing = true)
public @interface ConditionalOnLocalPipeline {}
