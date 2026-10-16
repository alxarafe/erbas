package com.alxarafe.erbas.modularity.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Associates every endpoint of a controller with its stable module descriptor ID. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface ModuleEndpoint {
    String value();
}
