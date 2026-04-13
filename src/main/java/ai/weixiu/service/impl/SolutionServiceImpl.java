package ai.weixiu.service.impl;

import ai.weixiu.entity.Solution;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.dto.SolutionDTO;
import ai.weixiu.repository.SolutionRepository;
import ai.weixiu.service.SolutionService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
public class SolutionServiceImpl implements SolutionService {

    private final SolutionRepository solutionRepository;
    private final String notFoundMessage = "解决方案不存在";

    @Override
    @Transactional
    public Solution save(SolutionDTO solutionDTO) {
        Solution solution = toEntity(solutionDTO);
        solution.setId(UUID.randomUUID().toString());
        return solutionRepository.save(solution);
    }

    @Override
    public Optional<Solution> findById(String id) {
        Optional<Solution> solution = solutionRepository.findById(id);
        if (!solution.isPresent()) {
            throw new NotFoundException(notFoundMessage);
        }
        return solution;
    }

    @Override
    public List<Solution> findAll() {
        return solutionRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        solutionRepository.deleteById(id);
    }

    @Override
    @Transactional
    public Solution update(SolutionDTO solutionDTO) {
        Solution solution = toEntity(solutionDTO);
        return solutionRepository.save(solution);
    }

    protected Solution toEntity(SolutionDTO solutionDTO) {
        Solution solution = new Solution();
        BeanUtils.copyProperties(solutionDTO, solution);
        return solution;
    }
}
