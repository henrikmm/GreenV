package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.port.ObjectUrlSigner;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** What a capture and a recorded example both do to a depth manifest before the site sees it. */
final class DemoManifests {

    private DemoManifests() {
    }

    /**
     * A copy of the manifest with every artifact link signed now, from its object key. The
     * handler's own links are twelve-hour links the API never hands out, and a recorded example
     * has none at all. A key outside {@code prefix}, or one that climbs out of it, is dropped
     * rather than signed, so a manifest can never be used to read another object.
     */
    static JsonNode signArtifacts(JsonNode manifest, String prefix, ObjectUrlSigner signer, Duration ttl) {
        if (!(manifest instanceof ObjectNode original)) {
            return manifest;
        }
        ObjectNode signed = original.deepCopy();
        JsonNode artifacts = signed.path("artifacts");
        if (artifacts.isArray()) {
            for (JsonNode artifact : artifacts) {
                if (!(artifact instanceof ObjectNode node)) {
                    continue;
                }
                String key = node.path("object_key").asString("");
                if (key.startsWith(prefix) && !key.contains("..")) {
                    node.put("url", signer.sign(key, ttl));
                } else {
                    node.putNull("url");
                }
            }
        }
        return signed;
    }
}
