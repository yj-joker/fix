package ai.weixiu.service.impl;

import ai.weixiu.entity.Fault;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.dto.FaultDTO;
import ai.weixiu.repository.FaultRepository;
import ai.weixiu.service.FaultService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
public class FaultServiceImpl implements FaultService {

    private final FaultRepository faultRepository;
    private final String notFoundMessage = "故障不存在";

    @Override
    @Transactional
    public Fault save(FaultDTO faultDTO) {
        Fault fault = toEntity(faultDTO);
        fault.setId(UUID.randomUUID().toString());
        return faultRepository.save(fault);
    }

    @Override
    public Optional<Fault> findById(String id) {
        Optional<Fault> fault = faultRepository.findById(id);
        if (!fault.isPresent()) {
            throw new NotFoundException(notFoundMessage);
        }
        return fault;
    }

    @Override
    public List<Fault> findAll() {
        return faultRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        faultRepository.deleteById(id);
    }

    @Override
    @Transactional
    public Fault update(FaultDTO faultDTO) {
        Fault fault = toEntity(faultDTO);
        return faultRepository.save(fault);
    }

    protected Fault toEntity(FaultDTO faultDTO) {
        Fault fault = new Fault();
        BeanUtils.copyProperties(faultDTO, fault);
        return fault;
    }
}
