package ai.weixiu.service.impl;

import ai.weixiu.entity.Solution;
import ai.weixiu.repository.SolutionRepository;
import ai.weixiu.service.SolutionService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class SolutionServiceImpl implements SolutionService {

    private final SolutionRepository solutionRepository;

    @Override
    public Solution save(Solution solution) {
        return solutionRepository.save(solution);
    }

    @Override
    public Optional<Solution> findById(String id) {
        return solutionRepository.findById(id);
    }

    @Override
    public List<Solution> findAll() {
        return solutionRepository.findAll();
    }

    @Override
    public void deleteById(String id) {
        solutionRepository.deleteById(id);
    }

    @Override
    public Solution update(Solution solution) {
        return solutionRepository.save(solution);
    }
}
