package ai.weixiu.service.impl;

import ai.weixiu.entity.Component;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.dto.ComponentDTO;
import ai.weixiu.repository.ComponentRepository;
import ai.weixiu.service.ComponentService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
public class ComponentServiceImpl implements ComponentService {

    private final ComponentRepository componentRepository;
    private final String notFoundMessage = "部件不存在";

    @Override
    @Transactional
    public Component save(ComponentDTO componentDTO) {
        Component component = toEntity(componentDTO);
        component.setId(UUID.randomUUID().toString());
        return componentRepository.save(component);
    }

    @Override
    public Optional<Component> findById(String id) {
        Optional<Component> component = componentRepository.findById(id);
        if (!component.isPresent()) {
            throw new NotFoundException(notFoundMessage);
        }
        return component;
    }

    @Override
    public List<Component> findAll() {
        return componentRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        componentRepository.deleteById(id);
    }

    @Override
    @Transactional
    public Component update(ComponentDTO componentDTO) {
        Component component = toEntity(componentDTO);
        return componentRepository.save(component);
    }

    protected Component toEntity(ComponentDTO componentDTO) {
        Component component = new Component();
        BeanUtils.copyProperties(componentDTO, component);
        return component;
    }
}
