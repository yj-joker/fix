package ai.weixiu.service;

import ai.weixiu.entity.MaintenanceManual;
import ai.weixiu.pojo.PageResult;
import ai.weixiu.pojo.dto.MaintenanceManualDTO;
import ai.weixiu.pojo.query.MaintenanceManualQuery;
import ai.weixiu.pojo.vo.MaintenanceManualVO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

public interface MaintenanceManualService extends IService<MaintenanceManual> {

    /**
     * 新增维修手册，并把上传文件保存到 MinIO 私有桶。
     *
     * @param maintenanceManualDTO 手册业务字段，id 不由前端决定
     * @param file                 手册源文件，仅支持配置允许的文档类型
     * @return 已落库的维修手册实体
     */
    MaintenanceManual add(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file);

    /**
     * 删除手册数据库记录、私有桶文件，并清理详情缓存。
     *
     * @param id 手册 id
     */
    void deleteById(Long id);

    /**
     * 更新手册基础信息；当 file 不为空时同步替换 MinIO 中的源文件。
     *
     * @param maintenanceManualDTO 待更新的手册字段，必须携带 id
     * @param file                 可选的新手册文件
     * @return 更新后的手册实体
     */
    MaintenanceManual update(MaintenanceManualDTO maintenanceManualDTO, MultipartFile file);

    /**
     * 查询手册基础信息。
     *
     * <p>该方法承载详情缓存、空值缓存和缓存互斥重建逻辑，排行榜展示也会复用它。</p>
     *
     * @param id 手册 id
     * @return 手册基础信息，不包含临时文件访问地址
     */
    MaintenanceManual getManualById(Long id);

    /**
     * 查询前端详情页需要的数据。
     *
     * <p>在基础信息上额外生成 MinIO 私有桶预签名地址，供前端临时访问文档。</p>
     *
     * @param id 手册 id
     * @return 详情响应对象
     */
    MaintenanceManualVO getManualDetailById(Long id);

    /**
     * 按筛选条件分页查询维修手册。
     *
     * @param query 分页、名称、状态和排序参数
     * @return 分页结果
     */
    PageResult<MaintenanceManual> getManualList(MaintenanceManualQuery query);
}
