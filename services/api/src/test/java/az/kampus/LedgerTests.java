package az.kampus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LedgerTests {
 @Test void crcMatchesPublishedCheckVector(){assertEquals("29B1",EmvQr.crc("123456789"));}
 @Test void corruptedQrIsRejected(){String q=EmvQr.create("m1","intent-test",1500);assertEquals("944",EmvQr.parse(q).get("53"));assertThrows(Problem.class,()->EmvQr.parse(q.replace("15.00","95.00")));}
 @Test void duplicateTlvTagsAreRejected(){String q="0002010002016304";q+=EmvQr.crc(q);String finalQr=q;assertThrows(Problem.class,()->EmvQr.parse(finalQr));}
 @Test void moneyParsingNeverTruncatesFractionalMinorUnits(){assertThrows(Problem.class,()->Store.amount(Store.m("amountMinor",12.5)));assertThrows(Problem.class,()->Store.amount(Store.m("amountMinor",-1)));assertEquals(1500,Store.amount(Store.m("amountMinor",1500)));}
}
