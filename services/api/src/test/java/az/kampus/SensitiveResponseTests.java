package az.kampus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static az.kampus.Store.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:sensitive;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"},webEnvironment=SpringBootTest.WebEnvironment.NONE)
class SensitiveResponseTests {
    @Autowired Engine engine;
    @Autowired Auth auth;
    @Autowired Store store;
    @MockitoBean Simulator sim;

    @Test @Transactional void revealNeverPersistsPanOrCvvAndCannotBeReplayed(){
        String pan="4169730000002048",cvv="842";
        when(sim.call(eq("/cards/reveal"),anyMap())).thenReturn(m("pan",pan,"cvv",cvv,"demo",true));
        var user=new Auth.User("u1","Aytən","+994501000001","USER","demo-device");
        String path="cards/card-demo/reveal";
        Map<String,Object> body=m("cardId","card-demo");
        var proof=auth.sca(user,m("pin","123456","payload",m("action",path,"data",body)));
        var result=(Map<?,?>)engine.write(user,path,body,"reveal-privacy-test",str(proof,"proof"));
        assertEquals(pan,result.get("pan"));
        String response=store.db.queryForObject("SELECT response FROM idempotency WHERE user_id='u1' AND idem_key='reveal-privacy-test'",String.class);
        assertFalse(response.contains(pan));assertFalse(response.contains(cvv));
        assertEquals(Boolean.TRUE,store.decode(response).get("sensitive"));
        var replay=assertThrows(Problem.class,()->engine.write(user,path,body,"reveal-privacy-test",str(proof,"proof")));
        assertEquals(410,replay.status);
    }
}
