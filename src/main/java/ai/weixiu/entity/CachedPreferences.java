package ai.weixiu.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CachedPreferences implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<MemoryPreference> userPreferences;

    private List<MemoryPreference> sessionPreferences;
}
