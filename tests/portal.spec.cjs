const {test,expect}=require('@playwright/test');
async function createMember(page){
 await page.goto('/');
 await page.getByLabel('체험 계정 만들기',{exact:true}).fill('시민 테스트');
 await page.getByRole('button',{name:'계정 만들기',exact:true}).click();
 await expect(page.locator('#member-welcome')).toContainText('시민 테스트');
}
test('무료 민원 접수, 검색, 상세, 새로고침 후 유지',async({page})=>{
 await createMember(page);
 await page.getByLabel('민원 제목 *',{exact:true}).fill('공원 가로등 점검 요청');
 await page.getByLabel('민원 내용 *',{exact:true}).fill('학습용 테스트: 가로등이 켜지지 않습니다.');
 await page.getByLabel('실제 민원이 아닌 학습용 체험임을 확인했습니다.').check();
 await page.getByRole('button',{name:'민원 접수하기'}).click();
 await expect(page.locator('.record')).toContainText('접수 완료');
 await page.reload();
 await expect(page.locator('.record')).toContainText('공원 가로등 점검 요청');
 await page.getByRole('button',{name:'공원 가로등 점검 요청',exact:true}).click();
 await expect(page.locator('#detail')).toBeVisible();
 await expect(page.locator('#detail')).toContainText('가로등이 켜지지 않습니다.');
 await page.getByRole('button',{name:'상세 닫기'}).click();
 await page.getByLabel('민원 제목 검색',{exact:true}).fill('없는 검색어');
 await expect(page.locator('#list')).toContainText('검색 결과가 없습니다.');
});
test('증명서 모의 결제와 영수증 조회',async({page})=>{
 await createMember(page);
 await page.getByLabel('민원 분야 *',{exact:true}).selectOption('CERTIFICATE');
 await page.getByLabel('민원 제목 *',{exact:true}).fill('증명서 발급 테스트');
 await page.getByLabel('민원 내용 *',{exact:true}).fill('실제 서류는 필요하지 않은 체험 신청');
 await page.getByLabel('실제 민원이 아닌 학습용 체험임을 확인했습니다.').check();
 await page.getByRole('button',{name:'민원 접수하기'}).click();
 await expect(page.locator('.record')).toContainText('결제 대기');
 page.on('dialog',dialog=>dialog.accept());
 await page.getByRole('button',{name:'1,000원 모의 결제',exact:true}).click();
 await expect(page.locator('.record')).toContainText('접수 완료');
 await page.getByRole('button',{name:'증명서 발급 테스트',exact:true}).click();
 await expect(page.locator('#detail')).toContainText('모의 결제 영수증');
});
test('휴대폰 포함 가로 넘침 없음',async({page})=>{
 await page.goto('/');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBeTruthy();
});
