package com.im.njams.sdk.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.im.njams.sdk.common.JsonSerializerFactory;

import java.io.IOException;

/**
 * Utilities for serializing to and parsing from JSON.
 *
 * @author cwinkler
 *
 */
public class JsonUtils {

    private JsonUtils() {
        // static only
    }

    /**
     * Parses the given JSON string and initializes a new object of the given type from that JSON.
     *
     * @param <T>
     *            The type of the object to return
     * @param json
     *            The JSON string to parse.
     * @param type
     *            The type of the object to be created from the given JSON.
     * @return New instance of the given type.
     * @throws Exception
     *             If parsing the given JSON into the target type failed.
     */
    public static <T> T parse(String json, Class<T> type) throws IOException {
        return getMapper().readValue(json, type);
    }

    /**
     * Serializes the given object to a JSON string-
     *
     * @param object
     *            The object to serialize.
     * @return JSON string representing the given object.
     * @throws Exception
     *             If serializing the object to JSON failed.
     */
    public static String serialize(Object object) throws JsonProcessingException {
        return getMapper().writeValueAsString(object);
    }

    private static ObjectMapper getMapper(){
        return JsonSerializerFactory.getDefaultMapper();
    }
}
