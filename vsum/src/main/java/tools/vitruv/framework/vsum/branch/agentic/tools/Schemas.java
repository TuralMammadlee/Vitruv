package tools.vitruv.framework.vsum.branch.agentic.tools;

import com.google.gson.JsonObject;

/**
 * Small helpers for building the JSON-Schema objects that describe tool
 * arguments, so individual tools do not repeat the boilerplate.
 */
public final class Schemas {

    private Schemas() {
    }

    /** Schema for a tool that takes no arguments: {@code {"type":"object","properties":{}}}. */
    public static JsonObject noArgs() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    /** Starts an object schema with an empty {@code properties} map to populate. */
    public static JsonObject object() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    /** Adds a typed property (with description) to an object schema built by {@link #object()}. */
    public static JsonObject property(JsonObject objectSchema, String name, String type, String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", type);
        prop.addProperty("description", description);
        objectSchema.getAsJsonObject("properties").add(name, prop);
        return objectSchema;
    }
}
