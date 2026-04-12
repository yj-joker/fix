package ai.weixiu.service.impl;

import ai.weixiu.pojo.entity.CaseRecord;
import ai.weixiu.repository.CaseRecordRepository;
import ai.weixiu.service.CaseRecordService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class CaseRecordServiceImpl implements CaseRecordService {

    private final CaseRecordRepository caseRecordRepository;

    @Override
    public CaseRecord save(CaseRecord caseRecord) {
        return caseRecordRepository.save(caseRecord);
    }

    @Override
    public Optional<CaseRecord> findById(String id) {
        return caseRecordRepository.findById(id);
    }

    @Override
    public List<CaseRecord> findAll() {
        return caseRecordRepository.findAll();
    }

    @Override
    public void deleteById(String id) {
        caseRecordRepository.deleteById(id);
    }

    @Override
    public CaseRecord update(CaseRecord caseRecord) {
        return caseRecordRepository.save(caseRecord);
    }
}
