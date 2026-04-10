package ai.weixiu.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ExcelUtils<T> {

    /*
    * 读取excel里对应数据的工具
    * */
    public static <T> List<T> readExcel(MultipartFile file, Class<T> clazz) {
        return readExcel(file, clazz, 0);
    }

    public static <T> List<T> readExcel(MultipartFile file, Class<T> clazz, int sheetNo) {
        List<T> list = new ArrayList<>();
        try {
            EasyExcel.read(file.getInputStream(), clazz, new ReadListener<T>() {
                @Override
                public void invoke(T data, AnalysisContext context) {
                    list.add(data);
                }
                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    log.info("Excel读取完成，共{}条数据", list.size());
                }
            }).sheet(sheetNo).headRowNumber(1).doReadSync();
        } catch (IOException e) {
            log.error("读取Excel文件失败", e);
        }
        return list;
    }
}
