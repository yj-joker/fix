package ai.weixiu.service.impl;

import ai.weixiu.entity.CaseRecord;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.dto.CaseRecordDTO;
import ai.weixiu.repository.CaseRecordRepository;
import ai.weixiu.service.CaseRecordService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
public class CaseRecordServiceImpl implements CaseRecordService {

    private final CaseRecordRepository caseRecordRepository;
    private final String notFoundMessage = "案例记录不存在";

    @Override
    @Transactional
    public CaseRecord save(CaseRecordDTO caseRecordDTO) {
        CaseRecord caseRecord = toEntity(caseRecordDTO);
        caseRecord.setId(UUID.randomUUID().toString());
        return caseRecordRepository.save(caseRecord);
    }

    @Override
    public Optional<CaseRecord> findById(String id) {
        Optional<CaseRecord> caseRecord = caseRecordRepository.findById(id);
        if (!caseRecord.isPresent()) {
            throw new NotFoundException(notFoundMessage);
        }
        return caseRecord;
    }

    @Override
    public List<CaseRecord> findAll() {
        return caseRecordRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        caseRecordRepository.deleteById(id);
    }

    @Override
    @Transactional
    public CaseRecord update(CaseRecordDTO caseRecordDTO) {
        CaseRecord caseRecord = toEntity(caseRecordDTO);
        return caseRecordRepository.save(caseRecord);
    }

    protected CaseRecord toEntity(CaseRecordDTO caseRecordDTO) {
        CaseRecord caseRecord = new CaseRecord();
        BeanUtils.copyProperties(caseRecordDTO, caseRecord);
        return caseRecord;
    }
}
