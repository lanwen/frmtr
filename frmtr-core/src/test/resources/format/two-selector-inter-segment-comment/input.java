package dev.example;

class TopicNameEncoder {

    String encodeTopic(String sourceTopic, String targetClusterPrefix) {
        return encode(sourceTopic)
            // strip the source cluster prefix before re-encoding
            .replaceAll(targetClusterPrefix, "");
    }
}
