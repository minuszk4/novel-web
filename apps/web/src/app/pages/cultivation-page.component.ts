import { Component, inject } from '@angular/core';
import { DemoStateService } from '../core/demo-state.service';

@Component({
  selector: 'app-cultivation-page',
  templateUrl: './cultivation-page.component.html',
})
export class CultivationPageComponent {
  protected readonly demo = inject(DemoStateService);
  protected readonly exampleXp = 620;
  protected readonly dailyCap = 120;

  get sampleRankIndex(): number {
    let index = 0;
    this.demo.cultivationRanks.forEach((rank, rankIndex) => {
      if (rank.threshold <= this.exampleXp) index = rankIndex;
    });
    return index;
  }

  get sampleRank(): string {
    return this.demo.cultivationRanks[this.sampleRankIndex]?.name ?? 'Phàm nhân';
  }

  get sampleNextRank(): string {
    return this.demo.cultivationRanks[this.sampleRankIndex + 1]?.name ?? 'Đã đạt mốc cuối';
  }

  get sampleNextThreshold(): number {
    return this.demo.cultivationRanks[this.sampleRankIndex + 1]?.threshold ?? this.exampleXp;
  }

  get sampleProgress(): number {
    const current = this.demo.cultivationRanks[this.sampleRankIndex]?.threshold ?? 0;
    const next = this.sampleNextThreshold;
    return next === current ? 100 : ((this.exampleXp - current) / (next - current)) * 100;
  }

  formatXp(value: number): string {
    return new Intl.NumberFormat('vi-VN').format(value);
  }
}
