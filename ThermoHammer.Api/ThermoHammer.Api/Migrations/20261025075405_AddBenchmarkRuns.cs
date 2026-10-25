using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace ThermoHammer.Api.Migrations
{
    /// <inheritdoc />
    public partial class AddBenchmarkRuns : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "BenchmarkRuns",
                columns: table => new
                {
                    Id = table.Column<int>(type: "int", nullable: false)
                        .Annotation("SqlServer:Identity", "1, 1"),
                    CreatedAt = table.Column<DateTime>(type: "datetime2", nullable: false),
                    SessionId = table.Column<int>(type: "int", nullable: false),
                    Hash = table.Column<string>(type: "nvarchar(max)", nullable: false),
                    BenchmarkVersion = table.Column<int>(type: "int", nullable: false),
                    DeviceManufacturer = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: false),
                    DeviceModel = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: false),
                    Os = table.Column<int>(type: "int", nullable: false),
                    OsVersion = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: false),
                    OsBuild = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: true),
                    SocModel = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: true),
                    GpuName = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: true),
                    AppVersion = table.Column<string>(type: "nvarchar(64)", maxLength: 64, nullable: true),
                    Test1Fps = table.Column<double>(type: "float", nullable: false),
                    Test1LowFps = table.Column<double>(type: "float", nullable: false),
                    Test2Fps = table.Column<double>(type: "float", nullable: false),
                    Test2LowFps = table.Column<double>(type: "float", nullable: false),
                    Test1Score = table.Column<int>(type: "int", nullable: false),
                    Test2Score = table.Column<int>(type: "int", nullable: false),
                    Score = table.Column<int>(type: "int", nullable: false),
                    ValidityFlags = table.Column<int>(type: "int", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_BenchmarkRuns", x => x.Id);
                    table.ForeignKey(
                        name: "FK_BenchmarkRuns_Sessions_SessionId",
                        column: x => x.SessionId,
                        principalTable: "Sessions",
                        principalColumn: "Id");
                });

            migrationBuilder.CreateIndex(
                name: "IX_BenchmarkRuns_BenchmarkVersion_Score",
                table: "BenchmarkRuns",
                columns: new[] { "BenchmarkVersion", "Score" });

            migrationBuilder.CreateIndex(
                name: "IX_BenchmarkRuns_SessionId",
                table: "BenchmarkRuns",
                column: "SessionId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "BenchmarkRuns");
        }
    }
}
