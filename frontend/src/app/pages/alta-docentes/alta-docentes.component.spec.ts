import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AltaDocentesComponent } from './alta-docentes.component';

describe('AltaDocentesComponent', () => {
  let component: AltaDocentesComponent;
  let fixture: ComponentFixture<AltaDocentesComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AltaDocentesComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(AltaDocentesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
