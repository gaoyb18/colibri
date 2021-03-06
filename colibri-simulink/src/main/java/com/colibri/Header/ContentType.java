package com.colibri.Header;
// This class provides the different content-type for Message Header
public enum ContentType {
    TEXT_PLAIN("text/plain"),
    APPLICATION_SPARQL("application/sparql-query"),
    APPLICATION_RESULT("application/sparql-result+json"),
    APPLICATION_RDF_XML("application/rdf+xml");

    private String type;

    ContentType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public static ContentType fromString(String text) {
        if (text != null) {
            for (ContentType type : ContentType.values()) {
                if (text.equalsIgnoreCase(type.type)) {
                    return type;
                }
            }
        }
        return TEXT_PLAIN;
    }
}
