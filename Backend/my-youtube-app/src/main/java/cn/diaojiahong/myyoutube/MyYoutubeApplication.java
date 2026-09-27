package cn.diaojiahong.myyoutube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableAsync
@EnableTransactionManagement
@EnableScheduling
@SpringBootApplication
public class MyYoutubeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MyYoutubeApplication.class, args);
	}

}
