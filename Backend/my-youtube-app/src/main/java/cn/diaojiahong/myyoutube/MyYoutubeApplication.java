package cn.diaojiahong.myyoutube;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableAsync
@EnableTransactionManagement
@EnableScheduling
@SpringBootApplication
@MapperScan("cn.diaojiahong.myyoutube.*.mapper")
public class MyYoutubeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyYoutubeApplication.class, args);
	}

}
