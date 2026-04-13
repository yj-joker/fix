package ai.weixiu.service.impl;

import ai.weixiu.entity.Device;
import ai.weixiu.exceprion.NotFoundException;
import ai.weixiu.pojo.dto.DeviceDTO;
import ai.weixiu.repository.DeviceRepository;
import ai.weixiu.service.DeviceService;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final String notFoundMessage = "设备不存在";

    @Override
    @Transactional
    public Device save(DeviceDTO deviceDTO) {
        Device device = toEntity(deviceDTO);
        device.setId(UUID.randomUUID().toString());
        return deviceRepository.save(device);
    }

    @Override
    public Optional<Device> findById(String id) {
        Optional<Device> device = deviceRepository.findById(id);
        if (!device.isPresent()) {
            throw new NotFoundException(notFoundMessage);
        }
        return device;
    }

    @Override
    public List<Device> findAll() {
        return deviceRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        deviceRepository.deleteById(id);
    }

    @Override
    @Transactional
    public Device update(DeviceDTO deviceDTO) {
        Device device = toEntity(deviceDTO);
        return deviceRepository.save(device);
    }

    protected Device toEntity(DeviceDTO deviceDTO) {
        Device device = new Device();
        BeanUtils.copyProperties(deviceDTO, device);
        return device;
    }
}
