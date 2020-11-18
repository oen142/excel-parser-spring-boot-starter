package com.github.cla9.excel.reader.worker;

import java.lang.reflect.Field;

/**
 * The interface Entity source.
 */
public interface EntitySource {
    /**
     * Is creation target field boolean.
     *
     * @param field the field
     * @return the boolean
     */
    boolean isCreationTargetField(final Field field);

    /**
     * Is candidate boolean.
     *
     * @param field the field
     * @return the boolean
     */
    boolean isCandidate(final Field field);

    /**
     * Is injection fields boolean.
     *
     * @param field the field
     * @return the boolean
     */
    boolean isInjectionFields(final Field field);
}
