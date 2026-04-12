package ai.weixiu.service.impl;

import ai.weixiu.pojo.entity.Device;
import ai.weixiu.repository.DeviceRepository;
import ai.weixiu.service.DeviceService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;

    @Override
    public Device save(Device device) {
        return deviceRepository.save(device);
    }

    @Override
    public Optional<Device> findById(String id) {
        return deviceRepository.findById(id);
    }

    @Override
    public List<Device> findAll() {
        return deviceRepository.findAll();
    }

    @Override
    public void deleteById(String id) {
        deviceRepository.deleteById(id);
    }

    @Override
    public Device update(Device device) {
        return deviceRepository.save(device);
    }
}
