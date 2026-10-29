package az.kampus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OpenBankingTests {
 @Test void merchantCategoryCodesMapOntoBudgetCategories(){
  assertEquals("Yemək",OpenBanking.category("5411","OUT"));
  assertEquals("Yemək",OpenBanking.category("5814","OUT"));
  assertEquals("Nəqliyyat",OpenBanking.category("4121","OUT"));
  assertEquals("Əyləncə",OpenBanking.category("4899","OUT"));
  assertEquals("Əyləncə",OpenBanking.category("7832","OUT"));
  assertEquals("Alış-veriş",OpenBanking.category("5651","OUT"));
  assertEquals("Kommunal",OpenBanking.category("4814","OUT"));
  assertEquals("Köçürmələr",OpenBanking.category("6012","OUT"));
  assertEquals("Digər",OpenBanking.category("6011","OUT"));
  assertEquals("Digər",OpenBanking.category("9999","OUT"));
 }
 @Test void incomingMoneyIsNeverTreatedAsSpending(){
  assertEquals("Gəlir",OpenBanking.category("5411","IN"));
  assertEquals("Gəlir",OpenBanking.category("0000","IN"));
 }
 @Test void transfersBetweenOwnAccountsAreNeitherIncomeNorSpending(){
  assertEquals("Daxili köçürmə",OpenBanking.category("0001","OUT"));
  assertEquals("Daxili köçürmə",OpenBanking.category("0001","IN"));
 }
}
