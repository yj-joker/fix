package ai.weixiu.service.impl;

import ai.weixiu.pojo.entity.Fault;
import ai.weixiu.repository.FaultRepository;
import ai.weixiu.service.FaultService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class FaultServiceImpl implements FaultService {

    private final FaultRepository faultRepository;

    @Override
    public Fault save(Fault fault) {
        return faultRepository.save(fault);
    }

    @Override
    public Optional<Fault> findById(String id) {
        return faultRepository.findById(id);
    }

    @Override
    public List<Fault> findAll() {
        return faultRepository.findAll();
    }

    @Override
    public void deleteById(String id) {
        faultRepository.deleteById(id);
    }

    @Override
    public Fault update(Fault fault) {
        return faultRepository.save(fault);
    }
}
