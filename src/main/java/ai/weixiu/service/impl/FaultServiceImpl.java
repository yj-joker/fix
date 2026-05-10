package ai.weixiu.service.impl;

import ai.weixiu.entity.Fault;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.FaultDTO;
import ai.weixiu.pojo.query.FaultQuery;
import ai.weixiu.pojo.vo.SolutionVO;
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

    /*
    * 分页查询故障的解决方案列表
    * */
    @Override
    public PageResult<SolutionVO> getSolutions(FaultQuery faultQuery) {
        int skip = faultQuery.getPage() * faultQuery.getSize();
        List<SolutionVO> records = faultRepository.getSolutionRecords(
            faultQuery.getFaultId(),
            faultQuery.getSolutionTitle(),
            skip,
            faultQuery.getSize()
        );
        Long total = faultRepository.getSolutionTotal(
            faultQuery.getFaultId(),
            faultQuery.getSolutionTitle()
        );
        PageResult<SolutionVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(faultQuery.getPage());
        result.setSize(faultQuery.getSize());
        return result;
    }

    /**
     * 将 DTO 转换为实体
     */
    protected Fault toEntity(FaultDTO faultDTO) {
        Fault fault = new Fault();
        BeanUtils.copyProperties(faultDTO, fault);
        return fault;
    }
}
