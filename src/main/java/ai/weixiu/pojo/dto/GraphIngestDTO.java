package ai.weixiu.pojo.dto;

import lombok.Data;
import java.util.List;

@Data
public class GraphIngestDTO {
    private Long manualId;
    private String documentId;
    private List<String> deviceNames;
    private List<ComponentItem> components;
    private List<FaultItem> faults;
    private List<SolutionItem> solutions;

    @Data public static class ComponentItem {
        private String tempId; private String name; private String specification; private String sourcePage;
    }
    @Data public static class FaultItem {
        private String tempId; private String name; private String description;
        private String severity; private String category;
        private String relatedComponentTempId; private String sourcePage;
    }
    @Data public static class SolutionItem {
        private String tempId; private String title; private String summary;
        private String toolsRequired; private String difficulty; private Integer estimatedTime;
        private String relatedFaultTempId;
    }
}
