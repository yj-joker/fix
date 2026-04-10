package ai.weixiu;

import ai.weixiu.pojo.entity.User;
import ai.weixiu.utils.ExcelUtils;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
class WeixiuApplicationTests {

    @Test
    void contextLoads() {

    }
    @Test
    public void testDebugRead() throws Exception {
        File file = new File("D:/test_users.xlsx");
        MultipartFile multipartFile = new MockMultipartFile(
                file.getName(), file.getName(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new FileInputStream(file)
        );

        List<User> users = ExcelUtils.readExcel(multipartFile, User.class);
        System.out.println("读取数量：" + users.size());

        // 打印前3条数据
        for (int i = 0; i < Math.min(3, users.size()); i++) {
            User u = users.get(i);
            System.out.println("第" + (i+1) + "条: username=" + u.getUsername()
                    + ", name=" + u.getName() + ", number=" + u.getNumber());
        }
    }
 @Test
    public void addTest()  {
     String path = "D:/test_users.xlsx";
     List<User> list = new ArrayList<>();

     for (int i = 1; i <= 10000; i++) {
         User user = new User();
         // 生成唯一用户名，使用i值而非i%100避免重复
         user.setUsername(String.valueOf(i));
         user.setName("用户" + i);
         user.setNumber("EMP" + String.format("%05d", i));
         user.setGender(i % 2);
         user.setType(i % 10 == 0 ? 1 : 0);
         user.setPhone("138" + String.format("%08d", i % 100000000));
         user.setHireDate(LocalDateTime.now().minusDays(i % 365));
         list.add(user);
     }

     EasyExcel.write(path, User.class)
             .sheet("用户数据")
             .doWrite(list);

     System.out.println("生成完成，共" + list.size() + "条数据，文件路径：" + path);
    }
}
