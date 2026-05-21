package ai.weixiu.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
/** 维修手册详情页响应对象。 */
public class MaintenanceManualVO {
    /** 手册 id。 */
    private Long id;

    /** 手册名称。 */
    private String manualName;

    /** 手册封面。 */
    private String manualImage;

    /** 手册描述。 */
    private String manualDesc;

    /** 上传时的原始文件名。 */
    private String fileName;

    /** 文件后缀类型，例如 .pdf、.doc、.docx。 */
    private String fileType;

    /** 文件大小，单位为字节。 */
    private Long fileSize;

    /** 私有桶文件的临时预签名访问地址，过期后需要重新查询详情。 */
    private String fileUrl;

    /** 手册状态。 */
    private Integer status;

    /** 上传人 id。 */
    private Long createdById;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最近更新时间。 */
    private LocalDateTime updatedAt;
}
