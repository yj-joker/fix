package ai.weixiu.service.impl;

import ai.weixiu.entity.Component;
import ai.weixiu.repository.ComponentRepository;
import ai.weixiu.service.ComponentService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class ComponentServiceImpl implements ComponentService {

    private final ComponentRepository componentRepository;

    @Override
    public Component save(Component component) {
        return componentRepository.save(component);
    }

    @Override
    public Optional<Component> findById(String id) {
        return componentRepository.findById(id);
    }

    @Override
    public List<Component> findAll() {
        return componentRepository.findAll();
    }

    @Override
    public void deleteById(String id) {
        componentRepository.deleteById(id);
    }

    @Override
    public Component update(Component component) {
        return componentRepository.save(component);
    }
}
