package ai.weixiu.service;

import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.pojo.vo.MaintenanceManualVO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

public interface MaintenanceManualService extends IService<MaintenanceManual> {

    MaintenanceManual add(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file);

    void deleteById(Long id);

    MaintenanceManual update(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file);

    MaintenanceManual getManualById(Long id);

    MaintenanceManualVO getManualDetailById(Long id);

    PageResult<MaintenanceManual> getManualList(MaintenanceManualQuery query);
}
