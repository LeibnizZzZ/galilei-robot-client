package mine.kartzan.galilei.model;

import lombok.Data;

/**
 * 机器狗业务文件上传完成通知：POST /api/robot/uploadFile
 *
 * <pre>
 * {
 *   "fileType": 1,                          // 文件业务类别：1 PCD图 / 2 灰度图 / 3 YAML文件
 *   "filePath": "/robotFile/test.pcd",      // 已通过 FTP 上传到服务器的文件路径
 *   "ip": "192.168.100.106",                // 机器狗 IP
 *   "port": 8888                            // 机器狗端口
 * }
 * </pre>
 *
 * <p>机器狗先通过 FTP 将业务文件（PCD 图、灰度图、YAML、日志等）上传到服务器指定目录，
 * 再调用本接口通知服务器「文件已上传」。
 */
@Data
public class FileUploadMessage {

    /** 文件业务类别：1 PCD图 / 2 灰度图 / 3 YAML文件 */
    private Integer fileType;

    /** 文件路径，通过 FTP 上传到服务器指定目录 */
    private String filePath;

    /** 机器狗 IP */
    private String ip;

    /** 机器狗端口 */
    private Integer port;
}
